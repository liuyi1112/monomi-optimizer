package edu.mit.cryptdb.tpch

import edu.mit.cryptdb._
import edu.mit.cryptdb.user._

// must be completely stateless
class TPCHTranslator extends Translator {

  def translateTableName(plainTableName: String) =
    plainTableName match {
      case "customer" | "lineitem" | "part" | "partsupp" |
           "region" | "nation" | "orders" | "supplier" =>
        plainTableName + "_enc_cryptdb_opt_with_det"
      case _ =>
        plainTableName
    }

  @inline
  private def colName(name: String, onion: Int): String =
    name + "_" + Onions.str(onion)

  def translateColumnName(
    plainTableName: String, plainColumnName: String, encOnion: Int) = {
    colName(plainTableName, encOnion)
  }

  def translatePrecomputedExprName(
    exprId: String, plainTableName: String, expr: SqlExpr, encOnion: Int): String = {

    // enumerate all the interesting ones

    // customer
    if (plainTableName == "customer") {
      expr match {
        case Substring(FieldIdent(None, "c_phone", _, _), 1, Some(2), _) =>
          return colName("c_phone_prefix", encOnion)
        case _ =>
      }

    // lineitem
    } else if (plainTableName == "lineitem") {
      expr match {
        case Extract(FieldIdent(None, "l_shipdate", _, _), YEAR, _) =>
          return colName("l_shipdate_year", encOnion)
        case Mult(FieldIdent(None, "l_extendedprice", _, _), Minus(IntLiteral(1, _), FieldIdent(None, "l_discount", _, _), _), _) =>
          return colName("l_disc_price", encOnion)
        case _ =>
      }

    // orders
    } else if (plainTableName == "orders") {
      expr match {
        case Extract(FieldIdent(None, "o_orderdate", _, _), YEAR, _) =>
          return colName("o_orderdate_year", encOnion)
        case _ =>
      }

    // partsupp
    } else if (plainTableName == "partsupp") {
      expr match {
        case Mult(FieldIdent(None, "ps_supplycost", _, _), FieldIdent(None, "ps_availqty", _, _), _) =>
          return colName("ps_volume", encOnion)
        case _ =>
      }
    }

    // default case
    exprId
  }

  private val FSPrefix = "/space/stephentu/data"

  private val _lineitem_agg0_seq = Seq(
    FieldIdent(None, "l_quantity"),
    FieldIdent(None, "l_extendedprice"),
    FieldIdent(None, "l_discount"),
    // (l_extendedprice * (1 - l_discount))
    Mult(FieldIdent(None, "l_extendedprice"), Minus(IntLiteral(1), FieldIdent(None, "l_discount"))),
  // ((l_extendedprice * (1 - l_discount)) * (1 + l_tax))
  Mult(Mult(FieldIdent(None, "l_extendedprice"), Minus(IntLiteral(1), FieldIdent(None, "l_discount"))), Plus(IntLiteral(1), FieldIdent(None, "l_tax"))))

  private val _lineitem_agg0_set = _lineitem_agg0_seq.toSet

  private val _lineitem_agg1_seq = Seq(
    Mult(FieldIdent(None, "l_extendedprice"), Minus(IntLiteral(1), FieldIdent(None, "l_discount"))))

  private val _lineitem_agg1_set = _lineitem_agg1_seq.toSet

  private val _lineitem_agg2_seq = Seq(
    Mult(FieldIdent(None, "l_extendedprice"), FieldIdent(None, "l_discount")))

  private val _lineitem_agg2_set = _lineitem_agg2_seq.toSet

  private val _customer_agg0_seq = Seq(
    FieldIdent(None, "c_acctbal"))

  private val _customer_agg0_set = _customer_agg0_seq.toSet

  private val _partsupp_agg0_seq = Seq(
    Mult(FieldIdent(None, "ps_supplycost"), FieldIdent(None, "ps_availqty")))

  private val _partsupp_agg0_set = _partsupp_agg0_seq.toSet

  def filenameForHomAggGroup(
    aggId: Int, plainDbName: String, plainTableName: String, aggs: Seq[SqlExpr]): String = {

    val p = FSPrefix + "/" + plainDbName + "/" + plainTableName + "_enc"

    // enumerate all the interesting ones

    // customer
    if (plainTableName == "customer") {
      if (aggs == _customer_agg0_seq)
        return p + "/row_pack/acctbal"
    }

    // lineitem
    else if (plainTableName == "lineitem") {
      if (aggs == _lineitem_agg0_seq)
        return p + "/row_col_pack/data"
      if (aggs == _lineitem_agg1_seq)
        return p + "/row_pack/disc_price"
      if (aggs == _lineitem_agg2_seq)
        return p + "/row_pack/revenue"
    }

    // partsupp
    else if (plainTableName == "partsupp") {
      if (aggs == _partsupp_agg0_seq)
        return p + "/row_pack/volume"
    }

    println("[WARNING] Unable to assign filename for reln %s:".format(plainTableName))
    println("  " + aggs.map(_.sql).mkString("[", ", ", "]"))

    // default case
    p + "/agg_" + aggId
  }

  def preferredHomAggGroup(
    plainTableName: String, group: Seq[SqlExpr]): Seq[SqlExpr] = {
    val fields = group.toSet
    plainTableName match {
      case "lineitem" =>
        if (fields == _lineitem_agg0_set)
          return _lineitem_agg0_seq

      case _ =>
    }
    group
  }

  // returns size of plaintext agg in BYTES
  def sizeInfoForHomAggGroup(
    plainTableName: String, group: Seq[SqlExpr]): Int = {
    plainTableName match {
      case "lineitem" =>
        if (group == _lineitem_agg0_seq)
          return (1256 / 8)
      case _ =>
    }
    (1024 / 8)
  }

}
