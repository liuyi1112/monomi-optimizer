package edu.mit.cryptdb

import scala.collection.mutable.{ ArrayBuffer, HashMap, Seq => MSeq, Map => MMap }

trait Generator extends Traversals with Transformers {

  private def topDownTraverseContext(start: Node, ctx: Context)(f: Node => Boolean) = {
    topDownTraversal(start) {
      case e if e.ctx == ctx => f(e)
      case _ => false
    }
  }

  private def topDownTransformContext(start: Node, ctx: Context)(f: Node => (Option[Node], Boolean)) = {
    topDownTransformation(start) {
      case e if e.ctx == ctx => f(e)
      case _ => (None, false)
    }
  }

  private def replaceWith(e: SqlExpr) = (Some(e), false)

  private val keepGoing = (None, true)

  private val stopGoing = (None, false)

  private def negate(f: Node => Boolean): Node => Boolean = (n: Node) => { !f(n) }

  private def resolveAliases(e: SqlExpr): SqlExpr = {
    topDownTransformation(e) {
      case FieldIdent(_, _, ProjectionSymbol(name, ctx), _) =>
        val expr1 = ctx.lookupProjection(name).get
        replaceWith(resolveAliases(expr1))
      case x => keepGoing
    }.asInstanceOf[SqlExpr]
  }

  private def splitTopLevelConjunctions(e: SqlExpr): Seq[SqlExpr] = {
    def split(e: SqlExpr, buffer: ArrayBuffer[SqlExpr]): ArrayBuffer[SqlExpr] = {
      e match {
        case And(lhs, rhs, _) =>
          split(lhs, buffer)
          split(rhs, buffer)
        case _ => buffer += e
      }
      buffer
    }
    split(e, new ArrayBuffer[SqlExpr]).toSeq
  }

  private def splitTopLevelClauses(e: SqlExpr): Seq[SqlExpr] = {
    def split(e: SqlExpr, buffer: ArrayBuffer[SqlExpr]): ArrayBuffer[SqlExpr] = {
      e match {
        case And(lhs, rhs, _) =>
          split(lhs, buffer)
          split(rhs, buffer)
        case Or(lhs, rhs, _) =>
          split(lhs, buffer)
          split(rhs, buffer)
        case _ => buffer += e
      }
      buffer
    }
    split(e, new ArrayBuffer[SqlExpr]).toSeq
  }

  private def foldTopLevelConjunctions(s: Seq[SqlExpr]): SqlExpr = {
    s.reduceLeft( (acc: SqlExpr, elem: SqlExpr) => And(acc, elem) )
  }

  // the return value is:
  // (relation name in e's ctx, global table name, expr out of the global table)
  //
  // NOTE: relation name is kind of misleading. consider the following example:
  //   SELECT ... FROM ( SELECT n1.x + n1.y AS foo FROM n1 WHERE ... ) AS t
  //   ORDER BY foo
  //
  // Suppose we precompute (x + y) for table n1. If we call findOnionableExpr() on
  // "foo", then we'll get (t, n1, x + y) returned. This works under the assumption
  // that we'll project the pre-computed (x + y) from the inner SELECT
  private def findOnionableExpr(e: SqlExpr): Option[(String, String, SqlExpr)] = {
    val ep = resolveAliases(e)
    val r = ep.getPrecomputableRelation
    if (r.isDefined) {
      // canonicalize the expr
      val e0 = topDownTransformation(ep) {
        case FieldIdent(_, name, _, _) => replaceWith(FieldIdent(None, name))
        case x                         => (Some(x.copyWithContext(null)), true)
      }.asInstanceOf[SqlExpr]
      Some((r.get._1, r.get._2, e0))
    } else {
      e match {
        case FieldIdent(_, _, ColumnSymbol(relation, name, ctx), _) =>
          if (ctx.relations(relation).isInstanceOf[SubqueryRelation]) {
            // recurse on the sub-relation
            ctx
              .relations(relation)
              .asInstanceOf[SubqueryRelation]
              .stmt
              .ctx
              .lookupProjection(name)
              .flatMap(findOnionableExpr)
              .map(_.copy(_1 = relation))
          } else None
        case _ => None
      }
    }
  }

  private def encTblName(t: String) = t + "$enc"

  def generatePlanFromOnionSet(stmt: SelectStmt, onionSet: OnionSet): PlanNode =
    generatePlanFromOnionSet0(stmt, onionSet, PreserveOriginal)

  abstract trait EncContext {
    def needsProjections: Boolean = true
  }

  case object PreserveOriginal extends EncContext

  case object PreserveCardinality extends EncContext {
    override def needsProjections = false
  }

  // onions are a mask of acceptable values (all onions must be non zero)
  // if require is true, the plan is guaranteed to return each projection
  // with the given onion. if require is false, the generator uses onions
  // as merely a hint about what onions are preferable
  case class EncProj(onions: Seq[Int], require: Boolean) extends EncContext {
    assert(onions.filter(_ == 0).isEmpty)
  }

  private def buildHomGroupPreference(uses: Seq[Seq[HomDesc]]):
    Map[String, Seq[Int]] = {
    // filter out all non-explicit hom-descriptors
    val uses0 = uses.filterNot(_.isEmpty).flatten

    // build counts for each unique descriptor
    val counts = new HashMap[String, MMap[Int, Int]]
    uses0.foreach { hd =>
      val m = counts.getOrElseUpdate(hd.table, new HashMap[Int, Int])
      m.put(hd.group, m.getOrElse(hd.group, 0) + 1)
    }

    counts.map {
      case (k, vs) => (k, vs.toSeq.sortWith(_._2 < _._2).map(_._1)) }.toMap
  }

  // returns a mapping of DependentFieldPlaceholder -> FieldIdent which was
  // replaced in stmt
  private def rewriteOuterReferences(stmt: SelectStmt):
    (SelectStmt, Seq[(DependentFieldPlaceholder, FieldIdent)]) = {
    val buf = new ArrayBuffer[(DependentFieldPlaceholder, FieldIdent)]
    (topDownTransformation(stmt) {
      case fi @ FieldIdent(_, _, ColumnSymbol(_, _, ctx), _) =>
        if (ctx.isParentOf(stmt.ctx)) {
          val rep = DependentFieldPlaceholder(buf.size)
          buf += ((rep, fi))
          replaceWith(rep)
        } else stopGoing
      case FieldIdent(_, _, _: ProjectionSymbol, _) =>
        // TODO: don't know how to handle this
        // also SQL might not even allow this
        throw new RuntimeException("TODO: implement me")
      case _ => keepGoing
    }.asInstanceOf[SelectStmt], buf.toSeq)
  }

  case class RewriteAnalysisContext(subrels: Map[String, PlanNode],
                                    homGroupPreferences: Map[String, Seq[Int]],
                                    groupKeys: Map[Symbol, (FieldIdent, OnionType)])

  case class HomDesc(table: String, group: Int, pos: Int)

  object CompProjMapping {
    final val empty = CompProjMapping(Map.empty, Map.empty)
  }

  case class CompProjMapping(
    projMap: Map[SqlProj, Int], subqueryProjMap: Map[SqlProj, Int]) {
    def values: Seq[Int] = projMap.values.toSeq ++ subqueryProjMap.values.toSeq
    def size: Int = values.size

    // creates a new update
    def update(idx: Map[Int, Int]): CompProjMapping = {
      CompProjMapping(
        projMap.map { case (p, i) => (p, idx(i)) }.toMap,
        subqueryProjMap.map { case (p, i) => (p, idx(i)) }.toMap)
    }
  }

  private def encLiteral(e: SqlExpr, o: Int): SqlExpr = {
    assert(BitUtils.onlyOne(o))
    assert(e.isLiteral)
    // TODO: actual encryption
    FunctionCall("encrypt", Seq(e, IntLiteral(o)))
  }

  // if encContext is PreserveOriginal, then the plan node generated faithfully
  // recreates the original statement- that is, the result set has the same
  // (unencrypted) type as the result set of stmt.
  //
  // if encContext is PreserveCardinality, then this function is free to generate
  // plans which only preserve the *cardinality* of the original statement. the
  // result set, however, is potentially left encrypted. this is useful for generating
  // plans such as subqueries to EXISTS( ... ) calls
  //
  // if encContext is EncProj, then stmt is expected to have exactly onions.size()
  // projection (this is asserted)- and plan node is written so it returns the
  // projections encrypted with the onion given by the onions sequence
  private def generatePlanFromOnionSet0(
    stmt: SelectStmt, onionSet: OnionSet, encContext: EncContext): PlanNode = {

    //println("generatePlanFromOnionSet0()")
    //println("stmt: " + stmt.sql)
    //println("encContext: " + encContext)

    encContext match {
      case EncProj(o, _) =>
        assert(stmt.ctx.projections.size == o.size)
      case _ =>
    }

    val subRelnGen = new NameGenerator("subrelation")

    // empty seq signifies wildcard
    def getSupportedHOMRowDescExpr(e: SqlExpr, subrels: Map[String, PlanNode]):
      Option[(SqlExpr, Seq[HomDesc])] = {
      if (e.isLiteral) {
        // TODO: coerce to integer?
        Some((FunctionCall("hom_row_desc_lit", Seq(e)), Seq.empty))
      } else {
        def procSubqueryRef(e: SqlExpr): Option[(SqlExpr, Seq[HomDesc])] = {
          e match {
            case fi @ FieldIdent(_, _, ColumnSymbol(relation, name, ctx), _)
              if ctx.relations(relation).isInstanceOf[SubqueryRelation] =>
              val idx =
                ctx
                  .relations(relation)
                  .asInstanceOf[SubqueryRelation]
                  .stmt.ctx.lookupNamedProjectionIndex(name).get
              // TODO: what do we do if the relation tupleDesc is in vector context
              assert(!subrels(relation).tupleDesc(idx).vectorCtx)
              val po = subrels(relation).tupleDesc(idx).onion
              if ((po.onion & Onions.HOM_ROW_DESC) != 0) {
                findOnionableExpr(e).flatMap { case (_, t, x) =>
                  val h = onionSet.lookupPackedHOM(t, x)
                  if (h.isEmpty) {
                    None
                  } else {
                    Some((fi.copyWithContext(null).asInstanceOf[FieldIdent].copy(qualifier = Some(relation)),
                          h.map { case (g, p) => HomDesc(t, g, p) }))
                  }
                }
              } else None
            case _ => None
          }
        }
        findOnionableExpr(e).flatMap { case (r, t, x) =>
          e match {
            case fi @ FieldIdent(_, _, ColumnSymbol(relation, name0, ctx), _)
              if ctx.relations(relation).isInstanceOf[SubqueryRelation] => procSubqueryRef(e)
            case _ =>
              val qual = if (r == t) encTblName(t) else r
              val h = onionSet.lookupPackedHOM(t, x)
              if (h.isEmpty) None
              else {
                Some((FieldIdent(Some(qual), "rowid"), h.map { case (g, p) => HomDesc(t, g, p) }))
              }
          }
        }
      }
    }

    def getSupportedExprConstraintAware(
      e: SqlExpr, o: Int,
      subrels: Map[String, PlanNode],
      groupKeys: Map[Symbol, (FieldIdent, OnionType)],
      aggContext: Boolean): Option[(SqlExpr, OnionType)] = {
      // need to check if we are constrained by group keys
      e match {
        case FieldIdent(_, _, sym, _) if aggContext =>
          groupKeys.get(sym) match {
            case Some((expr, o0)) if o0.isOneOf(o) => Some((expr, o0))
            case Some(_)                           => None // cannot support
            case None                              => getSupportedExpr(e, o, subrels)
          }
        case _ => getSupportedExpr(e, o, subrels)
      }
    }

    // return a *server-side expr* which is equivalent to e under onion o,
    // if possible. otherwise return None. o can be a bitmask of allowed
    // onions. use the return value to determine which onion was chosen.
    //
    // handles literals properly
    def getSupportedExpr(e: SqlExpr, o: Int, subrels: Map[String, PlanNode]):
      Option[(SqlExpr, OnionType)] = {

      e match {
        case e if e.isLiteral =>
          // easy case
          Onions.pickOne(o) match {
            case Onions.PLAIN => Some((e.copyWithContext(null).asInstanceOf[SqlExpr], PlainOnion))
            case o0           => Some((encLiteral(e, o0), OnionType.buildIndividual(o0))) // TODO: encryption
          }

        case d: DependentFieldPlaceholder =>
          val o0 = Onions.pickOne(o)
          Some((d.bind(o0), OnionType.buildIndividual(o0)))

        case _ =>
          def procSubqueryRef(e: SqlExpr) = {
            e match {
              case fi @ FieldIdent(_, _, ColumnSymbol(relation, name, ctx), _)
                if ctx.relations(relation).isInstanceOf[SubqueryRelation] =>
                val idx =
                  ctx
                    .relations(relation)
                    .asInstanceOf[SubqueryRelation]
                    .stmt.ctx.lookupNamedProjectionIndex(name).get
                // TODO: what do we do if the relation tupleDesc is in vector context
                assert(!subrels(relation).tupleDesc(idx).vectorCtx)
                val po = subrels(relation).tupleDesc(idx).onion
                if ((po.onion & o) != 0) {
                  Some((fi.copyWithContext(null).copy(qualifier = Some(relation)),
                        OnionType.buildIndividual(po.onion)))
                } else None
              case _ => None
            }
          }
          val e0 = findOnionableExpr(e)
          e0.flatMap { case (r, t, x) =>
            e match {
              case fi @ FieldIdent(_, _, ColumnSymbol(relation, name0, ctx), _)
                if ctx.relations(relation).isInstanceOf[SubqueryRelation] => procSubqueryRef(e)
              case _ =>
                onionSet.lookup(t, x).filter(y => (y._2 & o) != 0).map {
                  case (basename, o0) =>
                    val qual = if (r == t) encTblName(t) else r
                    val choice = Onions.pickOne(o0 & o)
                    val name = basename + "$" + Onions.str(choice)
                    ((FieldIdent(Some(qual), name), OnionType.buildIndividual(choice)))
                }
            }
          }.orElse {
            // TODO: this is hacky -
            // special case- if we looking at a field projection
            // from a subquery relation
            procSubqueryRef(e)
          }
      }
    }

    // ClientComputations leave the result of the expr un-encrypted
    case class ClientComputation
      (/* a client side expr for evaluation locally. the result of the expr is assumed
        * to be un-encrypted */
       expr: SqlExpr,

       /* unmodified, original expression, used later for cost analysis */
       origExpr: SqlExpr,

       /* additional encrypted projections needed for conjunction. the tuple is as follows:
        *   ( (original) expr from the client expr which will be replaced with proj,
        *     the actual projection to append to the *server side* query,
        *     the encryption onion which is being projected from the server side query,
        *     whether or not the proj is in vector ctx ) */
       projections: Seq[(SqlExpr, SqlProj, OnionType, Boolean)],

       subqueryProjections: Seq[(SqlExpr, SqlProj, OnionType, Boolean)],

       /* additional subqueries needed for conjunction. the tuple is as follows:
        *   ( (original) SelectStmt from the conjunction which will be replaced with PlanNode,
        *     the PlanNode to execute )
        */
       subqueries: Seq[(Subselect, PlanNode, Seq[(DependentFieldPlaceholder, FieldIdent)])]
      ) {

      /** Assumes both ClientComputations are conjunctions, and merges them into a single
       * computation */
      def mergeConjunctions(that: ClientComputation): ClientComputation = {
        ClientComputation(
          And(this.expr, that.expr),
          And(this.origExpr, that.origExpr),
          this.projections ++ that.projections,
          this.subqueryProjections ++ that.subqueryProjections,
          this.subqueries ++ that.subqueries)
      }

      // makes the client side expression SQL to go in a LocalTransform node
      def mkSqlExpr(mappings: CompProjMapping): SqlExpr = {
        val pmap  = projections.map(x => (x._1, x._2)).toMap
        val spmap = subqueryProjections.map(x => (x._1, x._2)).toMap
        val smap  = subqueries.zipWithIndex.map { case ((s, _, ss), i) =>
          // arguments to this subquery (as tuple positions)
          val args =
            ss.map(_._2).map(x => TuplePosition(mappings.subqueryProjMap(spmap(x))))
          (s, (i, args))
        }.toMap

        def testExpr(expr0: SqlExpr): Option[SqlExpr] = expr0 match {
          case Exists(s: Subselect, _) =>
            val r = smap(s)
            Some(ExistsSubqueryPosition(r._1, r._2))
          case s: Subselect            =>
            val r = smap(s)
            Some(SubqueryPosition(r._1, r._2))
          case e                       =>
            pmap.get(e).map { p => TuplePosition(mappings.projMap(p)) }
        }

        def mkExpr(expr0: SqlExpr): SqlExpr = {
          topDownTransformation(expr0) {
            case e: SqlExpr => testExpr(e).map(x => replaceWith(x)).getOrElse(keepGoing)
            case _          => keepGoing
          }.asInstanceOf[SqlExpr]
        }

        // TODO: why do we resolve aliases here?
        testExpr(expr).getOrElse(mkExpr(resolveAliases(expr)))
      }
    }

    var cur = stmt // the current statement, as we update it

    val _hiddenNames = new NameGenerator("_hidden")

    val newLocalFilters = new ArrayBuffer[ClientComputation]
    val localFilterPosMaps = new ArrayBuffer[CompProjMapping]

    val newLocalGroupBy = new ArrayBuffer[ClientComputation]
    val localGroupByPosMaps = new ArrayBuffer[CompProjMapping]

    // left is position in (original) projection to order by,
    // right is client comp
    //
    // NOTE: the position is NOT a position in the final projection list, but a logical
    //       projection position. this assumes no STAR projections
    val newLocalOrderBy = new ArrayBuffer[Either[Int, ClientComputation]]
    val localOrderByPosMaps = new ArrayBuffer[CompProjMapping]

    var newLocalLimit: Option[Int] = None

    // these correspond 1 to 1 with the original projections
    val projPosMaps = new ArrayBuffer[Either[(Int, OnionType), (ClientComputation, CompProjMapping)]]

    // these correspond 1 to 1 with the new projections in the encrypted
    // re-written query
    val finalProjs = new ArrayBuffer[(SqlProj, OnionType, Boolean)]

    case class RewriteContext(onions: Seq[Int], aggContext: Boolean) {
      def this(onion: Int, aggContext: Boolean) = this(Onions.toSeq(onion), aggContext)

      assert(!onions.isEmpty)
      assert(onions.filterNot(BitUtils.onlyOne).isEmpty)

      def inClear: Boolean = testOnion(Onions.PLAIN)
      def testOnion(o: Int): Boolean = !onions.filter { x => (x & o) != 0 }.isEmpty
      def restrict: RewriteContext = copy(onions = Seq(onions.head))

      def restrictTo(o: Int) = new RewriteContext(o, aggContext)
    }

    def rewriteExprForServer(
      expr: SqlExpr, rewriteCtx: RewriteContext, analysis: RewriteAnalysisContext):
      Either[(SqlExpr, OnionType), (Option[(SqlExpr, OnionType)], ClientComputation)] = {

      //println("rewriteExprForServer:")
      //println("  expr: " + expr.sql)
      //println("  rewriteCtx: " + rewriteCtx)

      // this is just a placeholder in the tree
      val cannotAnswerExpr = replaceWith(IntLiteral(1))

      def doTransform(e: SqlExpr, curRewriteCtx: RewriteContext):
        Either[(SqlExpr, OnionType), ClientComputation] = {

        def doTransformServer(e: SqlExpr, curRewriteCtx: RewriteContext):
          Option[(SqlExpr, OnionType)] = {

          val onionRetVal = new SetOnce[OnionType] // TODO: FIX THIS HACK
          var _exprValid = true
          def bailOut = {
            _exprValid = false
            cannotAnswerExpr
          }

          val newExpr = topDownTransformation(e) {

            case Or(l, r, _) if curRewriteCtx.inClear =>
              onionRetVal.set(OnionType.buildIndividual(Onions.PLAIN))
              CollectionUtils.optAnd2(
                doTransformServer(l, curRewriteCtx.restrictTo(Onions.PLAIN)),
                doTransformServer(r, curRewriteCtx.restrictTo(Onions.PLAIN))).map {
                  case ((l0, _), (r0, _)) => replaceWith(Or(l0, r0))
                }.getOrElse(bailOut)

            case And(l, r, _) if curRewriteCtx.inClear =>
              onionRetVal.set(OnionType.buildIndividual(Onions.PLAIN))
              CollectionUtils.optAnd2(
                doTransformServer(l, curRewriteCtx.restrictTo(Onions.PLAIN)),
                doTransformServer(r, curRewriteCtx.restrictTo(Onions.PLAIN))).map {
                  case ((l0, _), (r0, _)) => replaceWith(And(l0, r0))
                }.getOrElse(bailOut)

            case eq: EqualityLike if curRewriteCtx.inClear =>
              onionRetVal.set(OnionType.buildIndividual(Onions.PLAIN))

              def handleOneSubselect(ss: Subselect, expr: SqlExpr) = {
                assert(!expr.isInstanceOf[Subselect])
                val onions = Seq(Onions.PLAIN, Onions.DET, Onions.OPE)
                onions.foldLeft(None : Option[SqlExpr]) {
                  case (acc, onion) =>
                    acc.orElse {
                      val e0 = doTransformServer(expr, curRewriteCtx.restrictTo(onion))
                      e0.flatMap { case (expr, _) =>
                        generatePlanFromOnionSet0(
                          ss.subquery, onionSet, EncProj(Seq(onion), true)) match {
                          case RemoteSql(q0, _, _) => Some(eq.copyWithChildren(expr, Subselect(q0)))
                          case _                   => None
                        }
                      }
                    }
                }.map(replaceWith).getOrElse(bailOut)
              }

              (eq.lhs, eq.rhs) match {
                case (ss0 @ Subselect(q0, _), ss1 @ Subselect(q1, _)) =>
                  def mkSeq(o: Int) = {
                    Seq(generatePlanFromOnionSet0(q0, onionSet, EncProj(Seq(o), true)),
                        generatePlanFromOnionSet0(q1, onionSet, EncProj(Seq(o), true)))
                  }
                  val spPLAINs = mkSeq(Onions.PLAIN)
                  val spDETs   = mkSeq(Onions.DET)
                  val spOPEs   = mkSeq(Onions.OPE)

                  (spPLAINs(0), spPLAINs(1)) match {
                    case (RemoteSql(q0p, _, _), RemoteSql(q1p, _, _)) =>
                      replaceWith(eq.copyWithChildren(Subselect(q0p), Subselect(q1p)))
                    case _ =>
                      (spDETs(0), spDETs(1)) match {
                        case (RemoteSql(q0p, _, _), RemoteSql(q1p, _, _)) =>
                          replaceWith(eq.copyWithChildren(Subselect(q0p), Subselect(q1p)))
                        case _ =>
                          (spOPEs(0), spOPEs(1)) match {
                            case (RemoteSql(q0p, _, _), RemoteSql(q1p, _, _)) =>
                              replaceWith(eq.copyWithChildren(Subselect(q0p), Subselect(q1p)))
                            case _ => bailOut
                          }
                      }
                  }
                case (ss @ Subselect(_, _), rhs) => handleOneSubselect(ss, rhs)
                case (lhs, ss @ Subselect(_, _)) => handleOneSubselect(ss, lhs)
                case (lhs, rhs) =>
                  CollectionUtils.optAnd2(
                    doTransformServer(lhs, curRewriteCtx.restrictTo(Onions.PLAIN)),
                    doTransformServer(rhs, curRewriteCtx.restrictTo(Onions.PLAIN)))
                  .orElse(
                    CollectionUtils.optAnd2(
                      doTransformServer(lhs, curRewriteCtx.restrictTo(Onions.DET)),
                      doTransformServer(rhs, curRewriteCtx.restrictTo(Onions.DET))))
                  .orElse(
                    CollectionUtils.optAnd2(
                      doTransformServer(lhs, curRewriteCtx.restrictTo(Onions.OPE)),
                      doTransformServer(rhs, curRewriteCtx.restrictTo(Onions.OPE))))
                  .map {
                    case ((lfi, _), (rfi, _)) =>
                      replaceWith(eq.copyWithChildren(lfi, rfi))
                  }.getOrElse(bailOut)
              }

            // TODO: don't copy so much code from EqualityLike
            case ieq: InequalityLike if curRewriteCtx.inClear =>
              onionRetVal.set(OnionType.buildIndividual(Onions.PLAIN))

              def handleOneSubselect(ss: Subselect, expr: SqlExpr) = {
                assert(!expr.isInstanceOf[Subselect])
                val onions = Seq(Onions.PLAIN, Onions.OPE)
                onions.foldLeft(None : Option[SqlExpr]) {
                  case (acc, onion) =>
                    acc.orElse {
                      val e0 = doTransformServer(expr, curRewriteCtx.restrictTo(onion))
                      e0.flatMap { case (expr, _) =>
                        generatePlanFromOnionSet0(
                          ss.subquery, onionSet, EncProj(Seq(onion), true)) match {
                          case RemoteSql(q0, _, _) => Some(ieq.copyWithChildren(expr, Subselect(q0)))
                          case _                   => None
                        }
                      }
                    }
                }.map(replaceWith).getOrElse(bailOut)
              }

              (ieq.lhs, ieq.rhs) match {
                case (ss0 @ Subselect(q0, _), ss1 @ Subselect(q1, _)) =>
                  def mkSeq(o: Int) = {
                    Seq(generatePlanFromOnionSet0(q0, onionSet, EncProj(Seq(o), true)),
                        generatePlanFromOnionSet0(q1, onionSet, EncProj(Seq(o), true)))
                  }
                  val spPLAINs = mkSeq(Onions.PLAIN)
                  val spOPEs   = mkSeq(Onions.OPE)
                  (spPLAINs(0), spPLAINs(1)) match {
                    case (RemoteSql(q0p, _, _), RemoteSql(q1p, _, _)) =>
                      replaceWith(ieq.copyWithChildren(Subselect(q0p), Subselect(q1p)))
                    case _ =>
                      (spOPEs(0), spOPEs(1)) match {
                        case (RemoteSql(q0p, _, _), RemoteSql(q1p, _, _)) =>
                          replaceWith(ieq.copyWithChildren(Subselect(q0p), Subselect(q1p)))
                        case _ => bailOut
                      }
                  }
                case (ss @ Subselect(_, _), rhs) => handleOneSubselect(ss, rhs)
                case (lhs, ss @ Subselect(_, _)) => handleOneSubselect(ss, lhs)
                case (lhs, rhs) =>
                  CollectionUtils.optAnd2(
                    doTransformServer(lhs, curRewriteCtx.restrictTo(Onions.PLAIN)),
                    doTransformServer(rhs, curRewriteCtx.restrictTo(Onions.PLAIN)))
                  .orElse(
                    CollectionUtils.optAnd2(
                      doTransformServer(lhs, curRewriteCtx.restrictTo(Onions.OPE)),
                      doTransformServer(rhs, curRewriteCtx.restrictTo(Onions.OPE))))
                  .map {
                    case ((lfi, _), (rfi, _)) =>
                      replaceWith(ieq.copyWithChildren(lfi, rfi))
                  }.getOrElse(bailOut)
              }

            // TODO: handle subqueries
            case like @ Like(lhs, rhs, _, _) if curRewriteCtx.inClear =>
              onionRetVal.set(OnionType.buildIndividual(Onions.PLAIN))
              CollectionUtils.optAnd2(
                doTransformServer(lhs, curRewriteCtx.restrictTo(Onions.SWP)),
                doTransformServer(rhs, curRewriteCtx.restrictTo(Onions.SWP))).map {
                  case ((l0, _), (r0, _)) => replaceWith(FunctionCall("searchSWP", Seq(l0, r0, NullLiteral())))
                }.getOrElse(bailOut)

            // TODO: handle subqueries
            case in @ In(e, s, n, _) if curRewriteCtx.inClear =>
              onionRetVal.set(OnionType.buildIndividual(Onions.PLAIN))
              def tryOnion(o: Int) = {
                val t = (Seq(e) ++ s).map(x => doTransformServer(x, curRewriteCtx.restrictTo(o)))
                CollectionUtils.optSeq(t).map { s0 =>
                  replaceWith(In(s0.head._1, s0.tail.map(_._1), n))
                }
              }
              tryOnion(Onions.DET).orElse(tryOnion(Onions.OPE)).getOrElse(bailOut)

            case not @ Not(e, _) if curRewriteCtx.inClear =>
              onionRetVal.set(OnionType.buildIndividual(Onions.PLAIN))
              doTransformServer(e, curRewriteCtx.restrictTo(Onions.PLAIN))
                .map { case (e0, _) => replaceWith(Not(e0)) }.getOrElse(bailOut)

            case ex @ Exists(ss, _) if curRewriteCtx.inClear =>
              onionRetVal.set(OnionType.buildIndividual(Onions.PLAIN))
              generatePlanFromOnionSet0(ss.subquery, onionSet, PreserveCardinality) match {
                case RemoteSql(q, _, _) => replaceWith(Exists(Subselect(q)))
                case _                  => bailOut
              }

            case cs @ CountStar(_) if curRewriteCtx.inClear && curRewriteCtx.aggContext =>
              onionRetVal.set(OnionType.buildIndividual(Onions.PLAIN))
              replaceWith(CountStar())

            case cs @ CountExpr(e, d, _) if curRewriteCtx.inClear && curRewriteCtx.aggContext =>
              onionRetVal.set(OnionType.buildIndividual(Onions.PLAIN))
              doTransformServer(e, RewriteContext(Onions.toSeq(Onions.Countable), false))
                .map { case (e0, _) => replaceWith(CountExpr(e0, d)) }.getOrElse(bailOut)

            case m @ Min(f, _) if curRewriteCtx.testOnion(Onions.OPE) && curRewriteCtx.aggContext =>
              onionRetVal.set(OnionType.buildIndividual(Onions.OPE))
              doTransformServer(f, RewriteContext(Seq(Onions.OPE), false))
                .map { case (e0, _) => replaceWith(Min(e0)) }.getOrElse(bailOut)

            case m @ Max(f, _) if curRewriteCtx.testOnion(Onions.OPE) && curRewriteCtx.aggContext =>
              onionRetVal.set(OnionType.buildIndividual(Onions.OPE))
              doTransformServer(f, RewriteContext(Seq(Onions.OPE), false))
                .map { case (e0, _) => replaceWith(Max(e0)) }.getOrElse(bailOut)

            // TODO: we should do something about distinct
            case s @ Sum(f, d, _) if curRewriteCtx.aggContext =>
              def tryPlain = {
                doTransformServer(f, RewriteContext(Seq(Onions.PLAIN), false))
                  .map { case (e0, _) =>
                    onionRetVal.set(OnionType.buildIndividual(Onions.PLAIN))
                    replaceWith(Sum(e0, d))
                  }
              }
              def tryHom = {
                doTransformServer(f, RewriteContext(Seq(Onions.HOM), false))
                  .map { case (e0, _) =>
                    onionRetVal.set(OnionType.buildIndividual(Onions.HOM))
                    replaceWith(AggCall("hom_agg", Seq(e0)))
                  }
              }
              if (curRewriteCtx.inClear && curRewriteCtx.testOnion(Onions.HOM)) {
                tryPlain.orElse(tryHom).getOrElse(bailOut)
              } else if (curRewriteCtx.testOnion(Onions.HOM)) {
                tryHom.getOrElse(bailOut)
              } else if (curRewriteCtx.inClear) {
                tryPlain.getOrElse(bailOut)
              } else { bailOut }

            case CaseWhenExpr(cases, default, _) =>
              def tryWith(o: Int): Option[SqlExpr] = {
                def processCaseExprCase(c: CaseExprCase) = {
                  val CaseExprCase(cond, expr, _) = c
                  doTransformServer(cond, curRewriteCtx.restrictTo(Onions.PLAIN)).flatMap {
                    case (c0, _) =>
                      doTransformServer(expr, curRewriteCtx.restrictTo(o)).map {
                        case (e0, _) => CaseExprCase(c0, e0)
                      }
                  }
                }
                CollectionUtils.optSeq(cases.map(processCaseExprCase)).flatMap { cases0 =>
                  default match {
                    case Some(d) =>
                      doTransformServer(d, curRewriteCtx.restrictTo(o)).map {
                        case (d0, _) =>
                          onionRetVal.set(OnionType.buildIndividual(o))
                          CaseWhenExpr(cases0, Some(d0))
                      }
                    case None =>
                      onionRetVal.set(OnionType.buildIndividual(o))
                      Some(CaseWhenExpr(cases0, None))
                  }
                }
              }

              curRewriteCtx.onions.foldLeft( None : Option[SqlExpr] ) {
                case (acc, onion) => acc.orElse(tryWith(onion))
              }.map(replaceWith).getOrElse(bailOut)

            case e: SqlExpr if e.isLiteral =>
              onionRetVal.set(OnionType.buildIndividual(curRewriteCtx.onions.head))
              curRewriteCtx.onions.head match {
                case Onions.PLAIN => replaceWith(e.copyWithContext(null).asInstanceOf[SqlExpr])
                case o            => replaceWith(encLiteral(e, o))
              }

            case e: SqlExpr =>
              curRewriteCtx
                .onions
                .flatMap { case o =>
                  assert(BitUtils.onlyOne(o))
                  if (o == Onions.HOM_ROW_DESC) {
                    getSupportedHOMRowDescExpr(e, analysis.subrels)
                      .map { case (expr, hds) =>
                        (expr, HomRowDescOnion(hds.map(_.table).toSet.head))
                      }
                  } else {
                    getSupportedExprConstraintAware(
                      e, o, analysis.subrels,
                      analysis.groupKeys, curRewriteCtx.aggContext)
                  }
                }.headOption.map { case (expr, ret) =>
                  onionRetVal.set(ret)
                  replaceWith(expr)
                }.getOrElse(bailOut)

            case e => throw new Exception("should only have exprs under expr clause")
          }.asInstanceOf[SqlExpr]

          if (_exprValid) Some(newExpr, onionRetVal.get.get) else None
        }

        doTransformServer(e, curRewriteCtx) match {
          case Some((e0, o)) =>
            // nice, easy case
            Left((e0, o))
          case None =>
            // ugly, messy case- in this case, we have to project enough clauses
            // to compute the entirety of e locally. we can still make optimizations,
            // however, like replacing subexpressions within the expression with
            // more optimized variants

            // take care of all subselects first
            val subselects =
              new ArrayBuffer[(Subselect, PlanNode, Seq[(DependentFieldPlaceholder, FieldIdent)])]
            topDownTraversalWithParent(e) {
              case (Some(_: Exists), s @ Subselect(ss, _)) =>
                val (ss0, m) = rewriteOuterReferences(ss)
                val p = generatePlanFromOnionSet0(ss0, onionSet, PreserveCardinality)
                subselects += ((s, p, m))
                false
              case (_, s @ Subselect(ss, _)) =>
                val (ss0, m) = rewriteOuterReferences(ss)
                val p = generatePlanFromOnionSet0(ss0, onionSet, PreserveOriginal)
                subselects += ((s, p, m))
                false
              case _ => true
            }

            // return value is:
            // ( expr to replace in e -> ( replacement expr, seq( projections needed ) ) )
            def mkOptimizations(e: SqlExpr, curRewriteCtx: RewriteContext):
              Map[SqlExpr, (SqlExpr, Seq[(SqlExpr, SqlProj, OnionType, Boolean)])] = {

              val ret = new HashMap[SqlExpr, (SqlExpr, Seq[(SqlExpr, SqlProj, OnionType, Boolean)])]

              def handleBinopSpecialCase(op: Binop): Boolean = {
                val a = mkOptimizations(op.lhs, curRewriteCtx.restrictTo(Onions.ALL))
                val b = mkOptimizations(op.rhs, curRewriteCtx.restrictTo(Onions.ALL))
                a.get(op.lhs) match {
                  case Some((aexpr, aprojs)) =>
                    b.get(op.rhs) match {
                      case Some((bexpr, bprojs)) =>
                        ret += (op -> (op.copyWithChildren(aexpr, bexpr), aprojs ++ bprojs))
                        false
                      case _ => true
                    }
                  case _ => true
                }
              }

              // takes s and translates it into a server hom_agg expr, plus a
              // post-hom_agg-decrypt local sql projection (which extracts the individual
              // expression from the group)
              def handleHomSumSpecialCase(s: Sum) = {

                def pickOne(hd: Seq[HomDesc]): HomDesc = {
                  assert(!hd.isEmpty)
                  // need to pick which homdesc to use, based on given analysis preference
                  val m = hd.map(_.group).toSet
                  assert( hd.map(_.table).toSet.size == 1 )
                  val useIdx =
                    analysis.homGroupPreferences.get(hd.head.table).flatMap { prefs =>
                      prefs.foldLeft( None : Option[Int] ) {
                        case (acc, elem) =>
                          acc.orElse(if (m.contains(elem)) Some(elem) else None)
                      }
                    }.getOrElse(0)
                  hd(useIdx)
                }

                def findCommonHomDesc(hds: Seq[Seq[HomDesc]]): Seq[HomDesc] = {
                  assert(!hds.isEmpty)
                  hds.tail.foldLeft( hds.head.toSet ) {
                    case (acc, elem) if !elem.isEmpty => acc & elem.toSet
                    case (acc, _)                     => acc
                  }.toSeq
                }

                def translateForUniqueHomID(e: SqlExpr, aggContext: Boolean): Option[(SqlExpr, Seq[HomDesc])] = {
                  def procCaseExprCase(c: CaseExprCase): Option[(CaseExprCase, Seq[HomDesc])] = {
                    CollectionUtils.optAnd2(
                      doTransformServer(c.cond, RewriteContext(Seq(Onions.PLAIN), aggContext)),
                      translateForUniqueHomID(c.expr, aggContext)).map {
                        case ((l, _), (r, hd)) => (CaseExprCase(l, r), hd)
                      }
                  }
                  e match {
                    case Sum(f, _, _) if aggContext =>
                      translateForUniqueHomID(f, false).map {
                        case (f0, hd) if !hd.isEmpty =>
                          val hd0 = pickOne(hd)
                          (FunctionCall("hom_agg", Seq(f0, StringLiteral(hd0.table), IntLiteral(hd0.group))), Seq(hd0))
                      }

                    case CaseWhenExpr(cases, Some(d), _) =>
                      CollectionUtils.optAnd2(
                        CollectionUtils.optSeq(cases.map(procCaseExprCase)),
                        translateForUniqueHomID(d, aggContext)).flatMap {
                          case (cases0, (d0, hd)) =>
                            // check that all hds are not empty
                            val hds = cases0.map(_._2) ++ Seq(hd)
                            // should have at least one non-empty
                            assert(!hds.filterNot(_.isEmpty).isEmpty)
                            val inCommon = findCommonHomDesc(hds)
                            if (inCommon.isEmpty) None else {
                              Some((CaseWhenExpr(cases0.map(_._1), Some(d0)), inCommon))
                            }
                        }

                    case CaseWhenExpr(cases, None, _) =>
                      CollectionUtils.optSeq(cases.map(procCaseExprCase)).flatMap {
                        case cases0 =>
                          // check that all hds are not empty
                          val hds = cases0.map(_._2)
                          // should have at least one non-empty
                          assert(!hds.filterNot(_.isEmpty).isEmpty)
                          val inCommon = findCommonHomDesc(hds)
                          if (inCommon.isEmpty) None else {
                            Some((CaseWhenExpr(cases0.map(_._1), None), inCommon))
                          }
                      }

                    case e: SqlExpr =>
                      getSupportedHOMRowDescExpr(e, analysis.subrels)
                    case _ => None
                  }
                }

                translateForUniqueHomID(s, true).map {
                  case (expr, hds) =>
                    assert(hds.size == 1)
                    val id0 = _hiddenNames.uniqueId()
                    val expr0 = FieldIdent(None, id0)
                    val projs =
                      Seq((expr0, ExprProj(expr, None),
                           HomGroupOnion(hds(0).table, hds(0).group), false))
                    (FunctionCall("hom_get_pos", Seq(expr0, IntLiteral(hds(0).pos))), projs)
                }
              }

              topDownTraverseContext(e, e.ctx) {
                case avg @ Avg(f, d, _) if curRewriteCtx.aggContext =>
                  handleHomSumSpecialCase(Sum(f, d)).map { case (expr, projs) =>
                    val cnt = FieldIdent(None, _hiddenNames.uniqueId())
                    ret +=
                      (avg ->
                       (Div(expr, cnt), projs ++ Seq((cnt, ExprProj(CountStar(), None),
                        PlainOnion, false))))
                    false
                  }.getOrElse(true)

                // TODO: do something about distinct
                case s: Sum if curRewriteCtx.aggContext =>
                  //println("found sum s: " + s.sql)
                  handleHomSumSpecialCase(s).map { value =>
                      ret += (s -> value)
                      false
                  }.getOrElse(true)

                case _: SqlAgg =>
                  false // don't try to optimize exprs within an agg, b/c that
                        // just messes everything up

                case e: SqlExpr if e.isLiteral => false
                  // literals don't need any optimization

                case b: Div   => handleBinopSpecialCase(b)
                case b: Mult  => handleBinopSpecialCase(b)
                case b: Plus  => handleBinopSpecialCase(b)
                case b: Minus => handleBinopSpecialCase(b)

                case _ => true // keep traversing
              }
              ret.toMap
            }

            def mkProjections(e: SqlExpr): Seq[(SqlExpr, SqlProj, OnionType, Boolean)] = {
              val fields = resolveAliases(e).gatherFields
              def translateField(fi: FieldIdent, aggContext: Boolean) = {
                getSupportedExprConstraintAware(
                  fi, Onions.DET | Onions.OPE, analysis.subrels,
                  analysis.groupKeys, curRewriteCtx.aggContext)
                .getOrElse {
                  println("could not find DET/OPE enc for expr: " + fi)
                  println("orig: " + e.sql)
                  println("subrels: " + analysis.subrels)
                  throw new RuntimeException("should not happen")
                }
              }

              fields.map {
                case (f, false) =>
                  val (ft, o) = translateField(f, false)
                  (f, ExprProj(ft, None), o, false)
                case (f, true) =>
                  val (ft, o) = translateField(f, true)
                  (f, ExprProj(GroupConcat(ft, ","), None), o, true)
              }
            }

            val opts = mkOptimizations(e, curRewriteCtx)

            val e0ForProj = topDownTransformContext(e, e.ctx) {
              // replace w/ something dumb, so we can gather fields w/o worrying about it
              case e: SqlExpr if opts.contains(e) => replaceWith(IntLiteral(1))
              case _ => keepGoing
            }.asInstanceOf[SqlExpr]

            val e0 = topDownTransformContext(e, e.ctx) {
              case e: SqlExpr if opts.contains(e) => replaceWith(opts(e)._1)
              case _ => keepGoing
            }.asInstanceOf[SqlExpr]

            Right(
              ClientComputation(
                e0,
                e,
                opts.values.flatMap(_._2).toSeq ++ mkProjections(e0ForProj),
                subselects.map(_._3).flatMap(x => x.map(_._2).flatMap(mkProjections)).toSeq,
                subselects.toSeq))
          }
      }

      val exprs = splitTopLevelConjunctions(expr).map(x => doTransform(x, rewriteCtx))
      assert(!exprs.isEmpty)

      val sexprs = exprs.flatMap {
        case Left((s, o)) => Seq(s)
        case _            => Seq.empty
      }

      val sonions = exprs.flatMap {
        case Left((s, o)) => Seq(o)
        case _            => Seq.empty
      }

      val ccomps = exprs.flatMap {
        case Right(comp) => Seq(comp)
        case _           => Seq.empty
      }

      if (ccomps.isEmpty) {
        assert(!sexprs.isEmpty)
        Left(
          (foldTopLevelConjunctions(sexprs),
           if (sonions.size == 1) sonions.head else PlainOnion) )
      } else {
        var conjunctions: Option[ClientComputation] = None
        def mergeConjunctions(that: ClientComputation) = {
          conjunctions match {
            case Some(thiz) => conjunctions = Some(thiz mergeConjunctions that)
            case None => conjunctions = Some(that)
          }
        }
        ccomps.foreach(mergeConjunctions)
        Right(
          ((if (sexprs.isEmpty) None
            else Some((foldTopLevelConjunctions(sexprs),
                       if (sonions.size == 1) sonions.head else PlainOnion))),
          conjunctions.get))
      }
    }

    // subquery relations
    def findSubqueryRelations(r: SqlRelation): Seq[SubqueryRelationAST] =
      r match {
        case _: TableRelationAST         => Seq.empty
        case e: SubqueryRelationAST      => Seq(e)
        case JoinRelation(l, r, _, _, _) =>
          findSubqueryRelations(l) ++ findSubqueryRelations(r)
      }

    val subqueryRelations =
      cur.relations.map(_.flatMap(findSubqueryRelations)).getOrElse(Seq.empty)

    val subqueryRelationPlans =
      subqueryRelations.map { subq =>

        // build an encryption vector for this subquery
        val encVec = collection.mutable.Seq.fill(subq.subquery.ctx.projections.size)(0)

        def traverseContext(
          start: Node,
          ctx: Context,
          onion: Int,
          selectFn: (SelectStmt) => Unit): Unit = {

          if (start.ctx != ctx) return

          def add(exprs: Seq[(SqlExpr, Int)]): Boolean = {
            // look for references to elements from this subquery
            exprs.foreach {
              case (e, o) if o != Onions.DET =>
                e match {
                  case FieldIdent(_, _, ColumnSymbol(relation, name, ctx), _) =>
                    if (ctx.relations(relation).isInstanceOf[SubqueryRelation]) {
                      val sr = ctx.relations(relation).asInstanceOf[SubqueryRelation]
                      val projExpr = sr.stmt.ctx.lookupProjection(name)
                      assert(projExpr.isDefined)
                      findOnionableExpr(projExpr.get).foreach { case (_, t, x) =>
                        def doSet = {
                          val idx = sr.stmt.ctx.lookupNamedProjectionIndex(name)
                          assert(idx.isDefined)
                          encVec( idx.get ) |= o
                        }

                        if (o == Onions.HOM_ROW_DESC) {
                          if (!onionSet.lookupPackedHOM(t, x).isEmpty) doSet
                        } else {
                          onionSet.lookup(t, x).filter(y => (y._2 & o) != 0).foreach { _ => doSet }
                        }
                      }
                    }
                  case _ =>
                }
              case _ =>
            }
            true
          }

          def procExprPrimitive(e: SqlExpr, o: Int) = {
            def binopOp(l: SqlExpr, r: SqlExpr) = {
              getPotentialCryptoOpts(l, Onions.ALL).foreach(add)
              getPotentialCryptoOpts(r, Onions.ALL).foreach(add)
            }

            getPotentialCryptoOpts(e, o) match {
              case Some(exprs) => add(exprs)
              case None =>
                e match {
                  case Subselect(ss, _)                 => selectFn(ss)
                  case Exists(Subselect(ss, _), _)      => selectFn(ss)

                  // one-level deep binop optimizations
                  case Plus(l, r, _)                    => binopOp(l, r)
                  case Minus(l, r, _)                   => binopOp(l, r)
                  case Mult(l, r, _)                    => binopOp(l, r)
                  case Div(l, r, _)                     => binopOp(l, r)

                  case _                                =>
                }
            }
          }

          def procExpr(e: SqlExpr, o: Int) = {
            val clauses = splitTopLevelClauses(e)
            assert(!clauses.isEmpty)
            if (clauses.size == 1) {
              procExprPrimitive(clauses.head, o)
            } else {
              clauses.foreach(c => traverseContext(c, ctx, Onions.PLAIN, selectFn))
            }
          }

          start match {
            case e: SqlExpr          => procExpr(e, onion)
            case ExprProj(e, _, _)   => procExpr(e, onion)
            case SqlGroupBy(k, h, _) =>
              k.foreach(e => procExpr(e, onion))
              h.foreach(e => procExpr(e, Onions.PLAIN))
            case SqlOrderBy(k, _)    =>
              k.foreach(e => procExpr(e._1, onion))
            case _                   => /* no-op */
          }
        }

        def buildForSelectStmt(stmt: SelectStmt): Unit = {
          // TODO: handle relations
          val SelectStmt(p, _, f, g, o, _, ctx) = stmt
          p.foreach(e => traverseContext(e, ctx, Onions.ALL,              buildForSelectStmt))
          f.foreach(e => traverseContext(e, ctx, Onions.PLAIN,            buildForSelectStmt))
          g.foreach(e => traverseContext(e, ctx, Onions.Comparable,       buildForSelectStmt))
          o.foreach(e => traverseContext(e, ctx, Onions.IEqualComparable, buildForSelectStmt))
        }

        // TODO: not the most efficient implementation
        buildForSelectStmt(cur)

        (subq.alias,
         generatePlanFromOnionSet0(
           subq.subquery,
           onionSet,
           EncProj(encVec.map(x => if (x != 0) x else Onions.DET).toSeq, true)))
      }.toMap

    //println("subqplans: " + subqueryRelationPlans)

    // TODO: explicit join predicates (ie A JOIN B ON (pred)).
    // TODO: handle {LEFT,RIGHT} OUTER JOINS

    val finalSubqueryRelationPlans = new ArrayBuffer[PlanNode]

    // relations
    cur = cur.relations.map { r =>
      def rewriteSqlRelation(s: SqlRelation): SqlRelation =
        s match {
          case t @ TableRelationAST(name, a, _) => TableRelationAST(encTblName(name), a)
          case j @ JoinRelation(l, r, _, _, _)  =>
            // TODO: join clauses
            j.copy(left  = rewriteSqlRelation(l),
                   right = rewriteSqlRelation(r)).copyWithContext(null)
          case r @ SubqueryRelationAST(_, name, _) =>
            subqueryRelationPlans(name) match {
              case p : RemoteSql =>
                // if remote sql, then keep the subquery as subquery in the server sql,
                // while adding the plan's subquery children to our children directly
                finalSubqueryRelationPlans ++= p.subrelations
                SubqueryRelationAST(p.stmt, name)
              case p =>
                // otherwise, add a RemoteMaterialize node
                val name0 = subRelnGen.uniqueId()
                finalSubqueryRelationPlans += RemoteMaterialize(name0, p)
                TableRelationAST(name0, Some(name))
            }
        }
      cur.copy(relations = Some(r.map(rewriteSqlRelation)))
    }.getOrElse(cur)

    val homGroupChoices = new ArrayBuffer[Seq[HomDesc]]

    def gatherHomRowDesc(e: SqlExpr) = {
      topDownTraverseContext(e, e.ctx) {
        case e: SqlExpr =>
          getSupportedHOMRowDescExpr(e, subqueryRelationPlans).map { case (_, hds) =>
            homGroupChoices += hds
            false
          }.getOrElse(true)
        case _ => true
      }
    }

    topDownTraverseContext(cur, cur.ctx) {
      case Sum(f, _, _) =>
        gatherHomRowDesc(f)
        false
      case Avg(f, _, _) =>
        gatherHomRowDesc(f)
        false
      case _ => true
    }

    // figure out a total preference ordering for all the hom groups
    val analysis =
      RewriteAnalysisContext(subqueryRelationPlans,
                             buildHomGroupPreference(homGroupChoices.toSeq),
                             Map.empty)

    // filters
    cur = cur
      .filter
      .map(x => rewriteExprForServer(x, RewriteContext(Seq(Onions.PLAIN), false), analysis))
      .map {
        case Left((expr, onion)) =>
          assert(onion == PlainOnion)
          cur.copy(filter = Some(expr))

        case Right((optExpr, comp)) =>
          // TODO: this agg context stuff is sloppy and we don't really get it
          // right all the time. we should rework how rewriteExprForServer() passes
          // expressions back up to the caller for projection. really a combination of
          // both the caller's scope + the expression determines how to project
          val comp0 =
            if (cur.projectionsInAggContext) {
              // need to group_concat the projections then, because we have a groupBy context
              // TODO: we need to check if a group by's expr is being projected- if so,
              // we *don't* need to wrap in a GroupConcat (although it's technically still
              // correct, just wasteful)
              val ClientComputation(_, _, p, s, _) = comp
              comp.copy(
                projections = p.map {
                  case (expr, ExprProj(e, a, _), o, _) =>
                    (expr, ExprProj(GroupConcat(e, ","), a), o, true)
                },
                subqueryProjections = s.map {
                  case (expr, ExprProj(e, a, _), o, _) =>
                    (expr, ExprProj(GroupConcat(e, ","), a), o, true)
                })
            } else {
              comp
            }
          newLocalFilters += comp0
          optExpr.map { case (expr, onion) =>
            assert(onion == PlainOnion)
            cur.copy(filter = Some(expr)) }.getOrElse(cur.copy(filter = None))
      }.getOrElse(cur)

    // group by
    val groupKeys = new HashMap[Symbol, (FieldIdent, OnionType)]
    cur = {
      // need to check if we can answer the having clause
      val newGroupBy =
        cur
          .groupBy
          .map { gb =>
            gb.having.map { x =>
              (rewriteExprForServer(x, RewriteContext(Seq(Onions.PLAIN), true), analysis) match {
                case Left((expr, onion)) =>
                  assert(onion == PlainOnion)
                  gb.copy(having = Some(expr))
                case Right((optExpr, comp)) =>
                  newLocalGroupBy += comp
                  optExpr.map { case (expr, onion) =>
                    assert(onion  == PlainOnion)
                    gb.copy(having = Some(expr))
                  }.getOrElse(gb.copy(having = None))
              })
            }.getOrElse(gb)
          }

      // now check the keys
      val newGroupBy0 = newGroupBy.map(gb => gb.copy(keys = {
        gb.keys.map(k =>
          getSupportedExpr(k, Onions.OPE, subqueryRelationPlans)
          .orElse(getSupportedExpr(k, Onions.DET, subqueryRelationPlans))
          .map { case (fi: FieldIdent, o) =>
            k match {
              case FieldIdent(_, _, sym, _) =>
                assert(sym ne null)
                groupKeys += ((sym -> (fi, o)))
              case _ =>
            }
            fi
          }.getOrElse {
            println("Non supported expr: " + k)
            println("subq: " + subqueryRelationPlans)
            throw new RuntimeException("TODO: currently cannot support non-field keys")
          })
      }))
      cur.copy(groupBy = newGroupBy0)
    }

    val analysis1 = analysis.copy(groupKeys = groupKeys.toMap)

    // order by
    cur = {
      def handleUnsupported(o: SqlOrderBy) = {
        def getKeyInfoForExpr(f: SqlExpr) = {
          getSupportedExprConstraintAware(
            f, Onions.OPE,
            subqueryRelationPlans, groupKeys.toMap, true)
          .map { case (e, o) => (f, e, o) }
          .orElse {
            getSupportedExprConstraintAware(
              f, Onions.DET,
              subqueryRelationPlans, groupKeys.toMap, true)
            .map { case (e, o) => (f, e, o) }
          }
        }
        def mkClientCompFromKeyInfo(f: SqlExpr, fi: SqlExpr, o: OnionType) = {
          ClientComputation(f, f, Seq((f, ExprProj(fi, None), o, false)), Seq.empty, Seq.empty)
        }
        def searchProjIndex(e: SqlExpr): Option[Int] = {
          if (!e.ctx.projections.filter {
                case WildcardProjection => true
                case _ => false }.isEmpty) {
            // for now, if wildcard projection, don't do this optimization
            return None
          }
          e match {
            case FieldIdent(_, _, ProjectionSymbol(name, _), _) =>
              // named projection is easy
              e.ctx.lookupNamedProjectionIndex(name)
            case _ =>
              // actually do a linear search through the projection list
              e.ctx.projections.zipWithIndex.foldLeft(None : Option[Int]) {
                case (acc, (NamedProjection(_, expr, _), idx)) if e == expr =>
                  acc.orElse(Some(idx))
                case (acc, _) => acc
              }
          }
        }
        val aggCtx = !(cur.groupBy.isDefined && !newLocalFilters.isEmpty)
        newLocalOrderBy ++= (
          o.keys.map { case (k, _) =>
            searchProjIndex(k).map(idx => Left(idx)).getOrElse {
              getKeyInfoForExpr(k)
                .map { case (f, fi, o) => Right(mkClientCompFromKeyInfo(f, fi, o)) }
                .getOrElse {
                  // TODO: why do we need to resolveAliases() here only??
                  rewriteExprForServer(
                    resolveAliases(k),
                    RewriteContext(Seq(Onions.OPE), aggCtx),
                    analysis1) match {

                    case Left((expr, onion)) => Right(mkClientCompFromKeyInfo(k, expr, onion))
                    case Right((None, comp)) => Right(comp)
                    case _                   =>
                      // TODO: in this case we prob need to merge the expr as a
                      // projection of the comp instead
                      throw new RuntimeException("TODO: unimpl")
                  }
                }
              }
          }
        )
        None
      }
      val newOrderBy = cur.orderBy.flatMap(o => {
        if (newLocalFilters.isEmpty && newLocalGroupBy.isEmpty) {
          val mapped =
            o.keys.map(f =>
              (getSupportedExprConstraintAware(
                f._1, Onions.OPE, subqueryRelationPlans,
                groupKeys.toMap, true).map(_._1), f._2))
          if (mapped.map(_._1).flatten.size == mapped.size) {
            // can support server side order by
            Some(SqlOrderBy(mapped.map(f => (f._1.get, f._2))))
          } else {
            handleUnsupported(o)
          }
        } else {
          handleUnsupported(o)
        }
      })
      cur.copy(orderBy = newOrderBy)
    }

    // limit
    cur = cur.copy(limit = cur.limit.flatMap(l => {
      if (newLocalFilters.isEmpty &&
          newLocalGroupBy.isEmpty &&
          newLocalOrderBy.isEmpty) {
        Some(l)
      } else {
        newLocalLimit = Some(l)
        None
      }
    }))


    // projections
    cur = {

      val projectionCache = new HashMap[(SqlExpr, OnionType), (Int, Boolean)]
      def projectionInsert(p: SqlProj, o: OnionType, v: Boolean): Int = {
        assert(p.isInstanceOf[ExprProj])
        val ExprProj(e, _, _) = p
        val (i0, v0) =
          projectionCache.get((e.copyWithContext(null).asInstanceOf[SqlExpr], o))
          .getOrElse {
            // doesn't exist, need to insert
            val i = finalProjs.size
            finalProjs += ((p, o, v))

            // insert into cache
            projectionCache += ((e, o) -> (i, v))

            (i, v)
          }
        assert(v == v0)
        i0
      }

      def processClientComputation(comp: ClientComputation): CompProjMapping = {
        def proc(ps: Seq[(SqlExpr, SqlProj, OnionType, Boolean)]) =
          ps.map { case (_, p, o, v) =>
            (p, projectionInsert(p, o, v))
          }.toMap
       CompProjMapping(proc(comp.projections), proc(comp.subqueryProjections))
      }

      newLocalFilters.foreach { c =>
        localFilterPosMaps += processClientComputation(c)
      }

      newLocalGroupBy.foreach { c =>
        localGroupByPosMaps += processClientComputation(c)
      }

      newLocalOrderBy.foreach {
        case Left(_)  => localOrderByPosMaps += CompProjMapping.empty
        case Right(c) => localOrderByPosMaps += processClientComputation(c)
      }

      if (encContext.needsProjections) {
        cur.projections.zipWithIndex.foreach {
          case (ExprProj(e, a, _), idx) =>
            val onions = encContext match {
              case EncProj(o, r) =>
                if (r) Onions.toSeq(o(idx)) else Onions.completeSeqWithPreference(o(idx))
              case _             =>
                Onions.toSeq(Onions.ALL)
            }
            val aggCtx = !(cur.groupBy.isDefined && !newLocalFilters.isEmpty)
            rewriteExprForServer(e, RewriteContext(onions, aggCtx), analysis1) match {
              case Left((expr, onion)) =>
                val stmtIdx = projectionInsert(ExprProj(expr, a), onion, false)
                projPosMaps += Left((stmtIdx, onion))
              case Right((optExpr, comp)) =>
                assert(!optExpr.isDefined)
                val m = processClientComputation(comp)
                projPosMaps += Right((comp, m))
            }
          case (StarProj(_), _) => throw new RuntimeException("TODO: implement me")
        }
      }

      cur.copy(projections =
        finalProjs.map(_._1).toSeq ++ (if (!finalProjs.isEmpty) Seq.empty else Seq(ExprProj(IntLiteral(1), None))))
    }

    def wrapDecryptionNodeSeq(p: PlanNode, m: Seq[Int]): PlanNode = {
      val td = p.tupleDesc
      val s = m.flatMap { pos => if (Onions.isDecryptable(td(pos).onion.onion)) Some(pos) else None }.toSeq
      if (s.isEmpty) p else LocalDecrypt(s, p)
    }

    def wrapDecryptionNodeMap(p: PlanNode, m: CompProjMapping): PlanNode =
      wrapDecryptionNodeSeq(p, m.projMap.values.toSeq)

    def verifyPlanNode[P <: PlanNode](p: P): P = {
      val _unused = p.tupleDesc // has many sanity checks
      p
    }

    val tdesc =
      if (finalProjs.isEmpty) Seq(PosDesc(PlainOnion, false))
      else finalProjs.map { case (_, o, v) => PosDesc(o, v) }.toSeq

    // finalProjs is now useless, so clear it
    finalProjs.clear

    // --filters

    val stage1 =
      verifyPlanNode(
        newLocalFilters
          .zip(localFilterPosMaps)
          .foldLeft( RemoteSql(cur, tdesc, finalSubqueryRelationPlans.toSeq) : PlanNode ) {
            case (acc, (comp, mapping)) =>
              LocalFilter(comp.mkSqlExpr(mapping),
                          comp.origExpr,
                          wrapDecryptionNodeMap(acc, mapping),
                          comp.subqueries.map(_._2))
          })

    // --group bys

    val stage2 =
      verifyPlanNode(
        newLocalGroupBy.zip(localGroupByPosMaps).foldLeft( stage1 : PlanNode ) {
          case (acc, (comp, mapping)) =>
            LocalGroupFilter(comp.mkSqlExpr(mapping),
                             comp.origExpr,
                             wrapDecryptionNodeMap(acc, mapping),
                             comp.subqueries.map(_._2))
        })

    // --projections

    val stage3 =
      verifyPlanNode(
        if (encContext.needsProjections) {
          assert(!projPosMaps.isEmpty)

          val decryptionVec = (
            projPosMaps.flatMap {
              case Right((_, m)) => Some(m.values)
              case _             => None
            }.flatten ++ {
              projPosMaps.flatMap {
                case Left((p, o)) if !o.isPlain => Some(p)
                case _                          => None
              }
            }
          ).toSet.toSeq.sorted

          val s0 =
            if (decryptionVec.isEmpty) stage2
            else wrapDecryptionNodeSeq(stage2, decryptionVec)

          val projTrfms = projPosMaps.map {
            case Right((comp, mapping)) =>
              assert(comp.subqueries.isEmpty)
              Right(comp.mkSqlExpr(mapping))
            case Left((p, _)) => Left(p)
          }

          var offset = projTrfms.size
          val auxTrfmMSeq = newLocalOrderBy.zip(localOrderByPosMaps).flatMap {
            case (Left(p), _) => Seq.empty
            case (_, m)       =>
              val r = m.values.zipWithIndex.map {
                case (p, idx) => (p, offset + idx)
              }.toSeq
              offset += m.size
              r
          }
          val auxTrfms = auxTrfmMSeq.map { case (k, _) => Left(k) }

          val trfms = projTrfms ++ auxTrfms

          def isPrefixIdentityTransform(trfms: Seq[Either[Int, SqlExpr]]): Boolean = {
            trfms.zipWithIndex.foldLeft(true) {
              case (acc, (Left(p), idx)) => acc && p == idx
              case (acc, (Right(_), _))  => false
            }
          }

          // if trfms describes purely an identity transform, we can omit it
          val stage3 =
            if (trfms.size == s0.tupleDesc.size &&
                isPrefixIdentityTransform(trfms)) s0
            else LocalTransform(trfms, s0)

          // need to update localOrderByPosMaps with new proj info
          val updateIdx = auxTrfmMSeq.toMap

          (0 until localOrderByPosMaps.size).foreach { i =>
            localOrderByPosMaps(i) = localOrderByPosMaps(i).update(updateIdx)
          }

          stage3
        } else {
          assert(projPosMaps.isEmpty)
          stage2
        })

    val stage4 =
      verifyPlanNode({
        if (!newLocalOrderBy.isEmpty) {
          assert(newLocalOrderBy.size == stmt.orderBy.get.keys.size)
          assert(newLocalOrderBy.size == localOrderByPosMaps.size)

          // do all the local computations to materialize keys

          val decryptionVec =
            newLocalOrderBy.zip(localOrderByPosMaps).flatMap {
              case (Right(ClientComputation(expr, _, proj, subProjs, sub)), m) =>
                if (proj.size == 1 && m.size == 1 &&
                    proj.head._3.isOneOf(Onions.OPE) &&
                    proj.head._1 == expr && sub.isEmpty) Seq.empty else m.values
              case _ => Seq.empty
            }

          val orderTrfms =
            newLocalOrderBy.zip(localOrderByPosMaps).flatMap {
              case (Left(p), _)  => None
              case (Right(c), m) => Some(Right(c.mkSqlExpr(m)))
            }

          var offset = projPosMaps.size
          val orderByVec =
            newLocalOrderBy.zip(localOrderByPosMaps).map {
              case (Left(p), _)  => p
              case (Right(_), _) =>
                val r = offset
                offset += 1
                r
            }.zip(stmt.orderBy.get.keys).map { case (l, (_, t)) => (l, t) }

          if (orderTrfms.isEmpty) {
            LocalOrderBy(
              orderByVec,
              if (!decryptionVec.isEmpty) LocalDecrypt(decryptionVec, stage3) else stage3)
          } else {
            val allTrfms = (0 until projPosMaps.size).map { i => Left(i) } ++ orderTrfms
            LocalTransform(
              (0 until projPosMaps.size).map { i => Left(i) },
              LocalOrderBy(
                orderByVec,
                LocalTransform(allTrfms,
                  if (!decryptionVec.isEmpty) LocalDecrypt(decryptionVec, stage3) else stage3)))
          }
        } else {
          stage3
        }
      })

    val stage5 =
      verifyPlanNode(
        newLocalLimit.map(l => LocalLimit(l, stage4)).getOrElse(stage4))

    verifyPlanNode(
      encContext match {
        case PreserveCardinality => stage5
        case PreserveOriginal    =>

          // make sure everything is decrypted now
          val decryptionVec = stage5.tupleDesc.map(_.onion).zipWithIndex.flatMap {
            case (oo, idx) if !oo.isPlain => Some(idx)
            case _                        => None
          }

          assert(decryptionVec.isEmpty)
          stage5

        case EncProj(o, require) =>
          assert(stage5.tupleDesc.size == o.size)

          // optimization: see if stage5 is one layer covering a usable plan
          val usuablePlan = stage5 match {
            case LocalDecrypt(_, child) =>
              assert(child.tupleDesc.size == o.size)
              if (child.tupleDesc.map(_.onion).zip(o).filterNot {
                    case (onion, y) => onion.isOneOf(y)
                  }.isEmpty) Some(child) else None
            case _ => None
          }

          // special case if stage5 is usuable
          val stage5td = stage5.tupleDesc

          if (usuablePlan.isDefined) {
            usuablePlan.get
          } else if (!require || (stage5td.map(_.onion).zip(o).filter {
                case (onion, y) => onion.isOneOf(y)
              }.size == stage5td.size)) {
            stage5
          } else {

            //println("o: " + o)
            //println("stage5td: " + stage5td)
            //println("stage5: " + stage5.pretty)

            val dec =
              stage5td.map(_.onion).zip(o).zipWithIndex.flatMap {
                case ((onion, y), i) =>
                  if (onion.isOneOf(y) || onion.isPlain) Seq.empty else Seq(i)
              }

            val enc =
              stage5td.map(_.onion).zip(o).zipWithIndex.flatMap {
                case ((onion, y), i) =>
                  if (onion.isOneOf(y)) Seq.empty
                  else Seq((i, OnionType.buildIndividual(Onions.pickOne(y))))
              }

            val first  = if (dec.isEmpty) stage5 else verifyPlanNode(LocalDecrypt(dec, stage5))
            val second = if (enc.isEmpty) first  else verifyPlanNode(LocalEncrypt(enc, first))
            second
          }
      })
  }

  // if we want to answer e all on the server with onion constraints given by,
  // return the set of non-literal expressions and the corresponding bitmask of
  // acceptable onions (that is, any of the onions given is sufficient for a
  // server side rewrite), such that pre-computation is minimal
  private def getPotentialCryptoOpts(e: SqlExpr, o: Int):
    Option[Seq[(SqlExpr, Int)]] = {
    getPotentialCryptoOpts0(e, o)
  }

  private def getPotentialCryptoOpts0(e: SqlExpr, constraints: Int):
    Option[Seq[(SqlExpr, Int)]] = {

    def test(o: Int) = (constraints & o) != 0

    def containsNonPlain(o: Int) = (o & ~Onions.PLAIN) != 0

    def pickNonPlain(o: Int) = Onions.pickOne((o & ~Onions.PLAIN))

    // TODO: this is kind of hacky, we shouldn't have to special case this
    def specialCaseExprOpSubselect(expr: SqlExpr, subselectAgg: SqlAgg) = {
      if (!expr.isLiteral) {
        subselectAgg match {
          case Min(expr0, _) =>
            getPotentialCryptoOpts0(expr0, Onions.OPE)
          case Max(expr0, _) =>
            getPotentialCryptoOpts0(expr0, Onions.OPE)
          case _ => None
        }
      } else None
    }
    e match {
      case Or(l, r, _) if test(Onions.PLAIN) =>
        CollectionUtils.optAnd2(
          getPotentialCryptoOpts0(l, Onions.PLAIN),
          getPotentialCryptoOpts0(r, Onions.PLAIN)).map { case (l, r) => l ++ r }

      case And(l, r, _) if test(Onions.PLAIN) =>
        CollectionUtils.optAnd2(
          getPotentialCryptoOpts0(l, Onions.PLAIN),
          getPotentialCryptoOpts0(r, Onions.PLAIN)).map { case (l, r) => l ++ r }

      case eq: EqualityLike if test(Onions.PLAIN) =>
        (eq.lhs, eq.rhs) match {
          case (lhs, Subselect(SelectStmt(Seq(ExprProj(expr: SqlAgg, _, _)), _, _, _, _, _, _), _)) =>
            specialCaseExprOpSubselect(lhs, expr)
          case (Subselect(SelectStmt(Seq(ExprProj(expr: SqlAgg, _, _)), _, _, _, _, _, _), _), rhs) =>
            specialCaseExprOpSubselect(rhs, expr)
          case (lhs, rhs) =>
            CollectionUtils.optAnd2(
              getPotentialCryptoOpts0(lhs, Onions.DET),
              getPotentialCryptoOpts0(rhs, Onions.DET)).map { case (l, r) => l ++ r }
        }

      case ieq: InequalityLike if test(Onions.PLAIN) =>
        (ieq.lhs, ieq.rhs) match {
          case (lhs, Subselect(SelectStmt(Seq(ExprProj(expr: SqlAgg, _, _)), _, _, _, _, _, _), _)) =>
            specialCaseExprOpSubselect(lhs, expr)
          case (Subselect(SelectStmt(Seq(ExprProj(expr: SqlAgg, _, _)), _, _, _, _, _, _), _), rhs) =>
            specialCaseExprOpSubselect(rhs, expr)
          case (lhs, rhs) =>
            CollectionUtils.optAnd2(
              getPotentialCryptoOpts0(lhs, Onions.OPE),
              getPotentialCryptoOpts0(rhs, Onions.OPE)).map { case (l, r) => l ++ r }
        }

      case Like(lhs, rhs, _, _) if test(Onions.PLAIN) =>
        CollectionUtils.optAnd2(
          getPotentialCryptoOpts0(lhs, Onions.SWP),
          getPotentialCryptoOpts0(rhs, Onions.SWP)).map { case (l, r) => l ++ r }

      case Min(expr, _) if test(Onions.OPE) =>
        getPotentialCryptoOpts0(expr, Onions.OPE)

      case Max(expr, _) if test(Onions.OPE) =>
        getPotentialCryptoOpts0(expr, Onions.OPE)

      case s @ Sum(expr, _, _) if test(Onions.HOM_AGG) =>
        getPotentialCryptoOpts0(expr, Onions.HOM_ROW_DESC)

      case Avg(expr, _, _) if test(Onions.HOM_AGG) =>
        getPotentialCryptoOpts0(expr, Onions.HOM_ROW_DESC)

      case CountStar(_) => Some(Seq.empty)

      case CountExpr(expr, _, _) =>
        getPotentialCryptoOpts0(expr, Onions.DET)

      case CaseWhenExpr(cases, default, _) =>
        def procCaseExprCase(c: CaseExprCase, constraints: Int) = {
          CollectionUtils.optAnd2(
            getPotentialCryptoOpts0(c.cond, Onions.ALL),
            getPotentialCryptoOpts0(c.expr, constraints)).map { case (l, r) => l ++ r }
        }
        default match {
          case Some(d) =>
            CollectionUtils.optSeq(
              cases.map(c => procCaseExprCase(c, constraints)) ++
              Seq(getPotentialCryptoOpts0(d, constraints))).map(_.flatten)
          case None =>
            CollectionUtils.optSeq(
              cases.map(c => procCaseExprCase(c, constraints))).map(_.flatten)
        }

      case Not(e, _) =>
        getPotentialCryptoOpts0(e, Onions.PLAIN)

      case In(e, s, _, _) =>
        CollectionUtils.optSeq(
          (Seq(e) ++ s).map(x => getPotentialCryptoOpts0(x, Onions.DET))).map(_.flatten)

      case f : FieldIdent if containsNonPlain(constraints) =>
        Some(Seq((f, pickNonPlain(constraints))))

      case e : SqlExpr if e.isLiteral => Some(Seq.empty)

      case _ : DependentFieldPlaceholder => Some(Seq.empty)

      case e : SqlExpr
        if containsNonPlain(constraints) && e.getPrecomputableRelation.isDefined =>
        Some(Seq((e, pickNonPlain(constraints))))

      case e => None
    }
  }

  def generateCandidatePlans(stmt: SelectStmt): Seq[(PlanNode, EstimateContext)] = {
    val o = generateOnionSets(stmt)
    val perms = CollectionUtils.powerSetMinusEmpty(o)
    // merge all perms, then unique
    val candidates = perms.map(p => OnionSet.merge(p)).toSet.toSeq
    def fillOnionSet(o: OnionSet): OnionSet = {
      o.complete(stmt.ctx.defns)
    }
    def estimateContextFromOnionSet(o: OnionSet): EstimateContext = {
      EstimateContext(
        stmt.ctx.defns, o.getPrecomputedExprs, o.getRelationsWithHOMGroups.toSet)
    }
    candidates.map(fillOnionSet).map {
      o => (generatePlanFromOnionSet(stmt, o), estimateContextFromOnionSet(o))
    }.toMap.toSeq
  }

  def generateOnionSets(stmt: SelectStmt): Seq[OnionSet] = {

    def traverseContext(
      start: Node,
      ctx: Context,
      onion: Int,
      bootstrap: Seq[OnionSet],
      selectFn: (SelectStmt, Seq[OnionSet]) => Seq[OnionSet]): Seq[OnionSet] = {

      var workingSet : Seq[OnionSet] = bootstrap

      def add(exprs: Seq[(SqlExpr, Int)]): Boolean = {
        val e0 = exprs.map { case (e, o) => findOnionableExpr(e).map(e0 => (e0, o)) }.flatten
        if (e0.size == exprs.size) {
          e0.foreach {
            case ((_, t, e), o) =>
              if (o == Onions.HOM_ROW_DESC) {
                workingSet.foreach(_.addPackedHOMToLastGroup(t, e))
              } else {
                workingSet.foreach(_.add(t, e, o))
              }
          }
          true
        } else false
      }

      def procExprPrimitive(e: SqlExpr, o: Int) = {
        def binopOp(l: SqlExpr, r: SqlExpr) = {
          getPotentialCryptoOpts(l, Onions.ALL).foreach(add)
          getPotentialCryptoOpts(r, Onions.ALL).foreach(add)
        }

        getPotentialCryptoOpts(e, o) match {
          case Some(exprs) => add(exprs)
          case None =>
            e match {
              case Subselect(ss, _)                 => workingSet = selectFn(ss, workingSet)
              case Exists(Subselect(ss, _), _)      => workingSet = selectFn(ss, workingSet)

              // one-level deep binop optimizations
              case Plus(l, r, _)                    => binopOp(l, r)
              case Minus(l, r, _)                   => binopOp(l, r)
              case Mult(l, r, _)                    => binopOp(l, r)
              case Div(l, r, _)                     => binopOp(l, r)

              // TODO: more opts?
              case _                                =>
            }
        }
      }

      def procExpr(e: SqlExpr, o: Int) = {
        val clauses = splitTopLevelClauses(e)
        assert(!clauses.isEmpty)
        if (clauses.size == 1) {
          procExprPrimitive(clauses.head, o)
        } else {
          val sets = clauses.flatMap(c => traverseContext(c, ctx, Onions.PLAIN, bootstrap, selectFn))
          workingSet = workingSet.map { ws =>
            sets.foldLeft(ws) { case (acc, elem) => acc.merge(elem) }
          }
        }
      }

      start match {
        case e: SqlExpr          => procExpr(e, onion)
        case ExprProj(e, _, _)   => procExpr(e, onion)
        case SqlGroupBy(k, h, _) =>
          k.foreach(e => procExpr(e, onion))
          h.foreach(e => procExpr(e, Onions.PLAIN))
        case SqlOrderBy(k, _)    =>
          k.foreach(e => procExpr(e._1, onion))
        case _                   => /* no-op */
      }

      workingSet
    }

    def buildForSelectStmt(stmt: SelectStmt, bootstrap: Seq[OnionSet]): Seq[OnionSet] = {
      val SelectStmt(p, r, f, g, o, _, ctx) = stmt
      val s0 = {
        var workingSet = bootstrap.map(_.copy)
        p.foreach { e =>
          workingSet = traverseContext(e, ctx, Onions.ALL, workingSet, buildForSelectStmt)
        }
        workingSet
      }
      def processRelation(r: SqlRelation): Seq[OnionSet] =
        r match {
          case SubqueryRelationAST(subq, _, _) => buildForSelectStmt(subq, bootstrap.map(_.copy))
          case JoinRelation(l, r, _, _, _)     => processRelation(l) ++ processRelation(r)
          case _                               => Seq.empty
        }
      val s1 = r.map(_.flatMap(processRelation))
      val s2 = f.map(e => traverseContext(e, ctx, Onions.PLAIN, bootstrap.map(_.copy), buildForSelectStmt))
      val s3 = g.map(e => traverseContext(e, ctx, Onions.Comparable, bootstrap.map(_.copy), buildForSelectStmt))
      val s4 = o.map(e => traverseContext(e, ctx, Onions.IEqualComparable, bootstrap.map(_.copy), buildForSelectStmt))
      (s0 ++ s1.getOrElse(Seq.empty) ++ s2.getOrElse(Seq.empty) ++
      s3.getOrElse(Seq.empty) ++ s4.getOrElse(Seq.empty)).filterNot(_.isEmpty)
    }

    buildForSelectStmt(stmt, Seq(new OnionSet))
  }
}
