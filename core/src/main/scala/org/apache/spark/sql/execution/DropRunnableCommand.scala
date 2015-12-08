package org.apache.spark.sql.execution

import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.sources.{LogicalRelation, DropRelation}
import scala.util.control.Breaks._

/**
  * Execution for DROP TABLE command.
  *
  * @see [[DropRelation]]
  * @param allowNotExisting IF NOT EXISTS.
  * @param relation Relation to drop.
  * @param cascade CASCADE.
  */
private[sql] case class DropRunnableCommand(
    allowNotExisting: Boolean,
    relation: Option[DropRelation],
    cascade: Boolean)
  extends RunnableCommand {

  override def run(sqlContext: SQLContext): Seq[Row] = {
    relation match {
      case None => allowNotExisting match {
        case false => sys.error("Could not find any matching tables to drop.")
        case true => Seq.empty[Row]
      }
      case Some(existingRelation) => executeDrop(sqlContext, existingRelation)
    }
  }

  private def executeDrop(sqlContext: SQLContext, relation: DropRelation): Seq[Row] =
    getReferencingRelations(sqlContext, relation) match {
      case tablesToDelete if tablesToDelete.isEmpty =>
        sys.error("Could not find any matching tables to drop.")
      case tablesToDelete if tablesToDelete.length == 1 =>
        sqlContext.dropTempTable(tablesToDelete.head)
        relation.dropTable()
        Seq.empty[Row]
      case tablesToDelete =>
        if(cascade) {
          tablesToDelete.foreach(sqlContext.dropTempTable)
          relation.dropTable()
          Seq.empty[Row]
        } else {
          sys.error("Can not drop because more than one relation has " +
            s"references to the target relation: ${tablesToDelete.mkString(",")}. " +
            s"to force drop use 'CASCADE'.")
        }
    }

  private def getReferencingRelations(sqlContext: SQLContext,
                                      relation: DropRelation): Seq[String] = {
    var tablesToDelete: Seq[String] = Seq.empty[String]
    for (t <- sqlContext.tableNames()) {
      val r = sqlContext.catalog.lookupRelation(t :: Nil)
      tablesToDelete ++= r.flatMap({
        case LogicalRelation(inner) if inner == relation =>
          Some(t)
        case _ => None
      })
      if(r.equals(relation)) {
        sqlContext.dropTempTable(t)
        break()
      }
    }
    tablesToDelete
  }
}
