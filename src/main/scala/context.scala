package edu.mit.cryptdb

import scala.collection.mutable.{ArrayBuffer, HashMap}

abstract trait Symbol {
  val ctx: Context
  val tpe: DataType
}

// a symbol corresponding to a reference to a column from a relation in this context.
// note that this column is not necessarily projected in this context
case class ColumnSymbol(relation: String, column: String, ctx: Context, tpe: DataType) extends Symbol {
  assert(tpe != UnknownType)
  def fieldPosition: Int =
    ctx.lookupPosition(relation, column).getOrElse {
      0  // precomputed values go here- we assume field pos of 0
    }
  def partOfPK: Boolean =
    ctx.lookupPartOfPK(relation, column).getOrElse {
      false  // precomputed values go here- we assume they are not part of pkey
    }
}

// a symbol corresponding to a reference to a projection in this context.
// note b/c of sql scoping rules, this symbol can only appear in the keys of a
// {group,order} by clause (but not the having clause of a group by)
case class ProjectionSymbol(name: String, ctx: Context, tpe: DataType) extends Symbol

abstract trait ProjectionType
case class NamedProjection(name: String, expr: SqlExpr, pos: Int) extends ProjectionType
case object WildcardProjection extends ProjectionType

class Context(val parent: Either[(Definitions, Statistics), Context]) {
  val relations = new HashMap[String, Relation]
  val projections = new ArrayBuffer[ProjectionType]

  // is this context a parent of that context?
  def isParentOf(that: Context): Boolean = {
    var cur = that.parent.right.toOption.orNull
    while (cur ne null) {
      if (cur eq this) return true
      cur = cur.parent.right.toOption.orNull
    }
    false
  }

  def lookupPosition(relation: String, column: String): Option[Int] = {
    relations.get(relation).flatMap {
      case TableRelation(r) => defns.lookupPosition(r, column)
      case _                => None
    }
  }

  def lookupPartOfPK(relation: String, column: String): Option[Boolean] = {
    relations.get(relation).flatMap {
      case TableRelation(r) => defns.lookupPartOfPK(r, column)
      case _                => None
    }
  }

  // lookup a projection with the given name in this context.
  // if there are multiple projections with
  // the given name, then it is undefined which projection is returned.
  // this also returns projections which exist due to wildcard projections.
  def lookupProjection(name: String): Option[SqlExpr] =
    lookupProjection0(name, true)

  // ignores wildcards, and treats preceeding wildcards as a single projection
  // in terms of index calculation
  def lookupNamedProjectionIndex(name: String): Option[Int] = {
    projections.zipWithIndex.flatMap {
      case (NamedProjection(n0, _, _), idx) if n0 == name => Some(idx)
      case _ => None
    }.headOption
  }

  private def lookupProjection0(name: String, allowWildcard: Boolean): Option[SqlExpr] = {
    projections.flatMap {
      case NamedProjection(n0, expr, _) if n0 == name => Some(expr)
      case WildcardProjection if allowWildcard =>
        def lookupRelation(r: Relation): Option[SqlExpr] = r match {
          case TableRelation(t) =>
            defns.lookup(t, name).map(tc => {
              FieldIdent(Some(t), name, ColumnSymbol(t, name, this, tc.tpe), this)
            })
          case SubqueryRelation(s) =>
            s.ctx.projections.flatMap {
              case NamedProjection(n0, expr, _) if n0 == name => Some(expr)

              case WildcardProjection =>
                assert(allowWildcard)
                s.ctx.lookupProjection(name)

              case _ => None
            }.headOption
        }
        relations.flatMap {
          case (_, r) => lookupRelation(r)
        }
      case _ => None
    }.headOption
  }

  val (defns, stats) = lookupInfo()

  private def lookupInfo(): (Definitions, Statistics) =
    parent match {
      case Left((d, s)) => (d, s)
      case Right(p)     => p.lookupInfo()
    }

  // finds an column by name
  def lookupColumn(qual: Option[String], name: String, inProjScope: Boolean): Seq[Symbol] =
    lookupColumn0(qual, name, inProjScope, None)

  private def lookupColumn0(qual: Option[String],
                            name: String,
                            inProjScope: Boolean,
                            topLevel: Option[String]): Seq[Symbol] = {

    def lookupRelation(topLevel: String, r: Relation): Seq[Symbol] = r match {
      case TableRelation(t) =>
        defns.lookup(t, name).map(tc => ColumnSymbol(topLevel, name, this, tc.tpe)).toSeq
      case SubqueryRelation(s) =>
        s.ctx.projections.flatMap {
          case NamedProjection(n0, expr, _) if n0 == name =>
            if (expr.getType.tpe == UnknownType) {
              System.err.println("bad expr: " + expr)
            }
            Seq(ColumnSymbol(topLevel, name, this, expr.getType.tpe))
          case WildcardProjection =>
            s.ctx.lookupColumn0(None, name, inProjScope, Some(topLevel))
          case _ => Seq.empty
        }
    }
    val res = qual match {
      case Some(q) =>
        //System.err.println("relations.get(q = %s): ".format(q) + relations.get(q))
        relations.get(q).map(x => lookupRelation(topLevel.getOrElse(q), x)).getOrElse(Seq.empty)
      case None =>
        // projection lookups can only happen if no qualifier, and we currently give preference
        // to column lookups first - TODO: verify if this is correct sql scoping rules.
        val r = relations.flatMap { case (r, x) => lookupRelation(topLevel.getOrElse(r), x) }.toSeq
        if (r.isEmpty) {
          lookupProjection0(name, false).map(
            p => Seq(ProjectionSymbol(name, this, p.getType.tpe))).getOrElse(Seq.empty)
        } else r
    }

    if (res.isEmpty) {
      // TODO:
      // lookup in parent- assume lookups in parent scope never reads projections-
      // ie we currently assume no sub-selects in {group,order} by clauses
      parent.right.toOption.map(_.lookupColumn(qual, name, false)).getOrElse(Seq.empty)
    } else res
  }

  // HACK: don't use default toString(), so that built-in ast node's
  // toString can be used for structural equality
  override def toString = "Context"
}
