package org.freedomandy.mole.synchronizers

import com.typesafe.config.Config
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.freedomandy.mole.commons.exceptions.InvalidInputException
import org.freedomandy.mole.commons.synchronizers.Synchronizer
import org.freedomandy.mole.commons.utils.PluginLoader

/**
  * @author Andy Huang on 2018/6/4
  */
case class Loader(session: SparkSession, config: Config) {
  import collection.JavaConversions._
  private val sinkList: List[Config] = config.getObjectList("synchronize.sink").toList.map(_.toConfig)

  def loadPlugins(): Function[Config, (DataFrame, String)=> Unit] = {
    def getPartialFunction(synchronizer: Synchronizer): PartialFunction[Config, (DataFrame, String) => Unit] = {
      case config: Config => synchronizer.sync(config)
    }
    def getTransformFunc(customTransform: Option[PartialFunction[Config, (DataFrame, String) => Unit]]): Function[Config, (DataFrame, String)=> Unit] = {
      val basePF: PartialFunction[Config, (DataFrame, String) => Unit] = {
        case config: Config if config.getString("type") == EsSynchronizer.sinkName =>
          EsSynchronizer.sync(config)
        case config: Config if config.getString("type") == MongoSynchronizer.sinkName =>
          MongoSynchronizer.sync(config)
        case config: Config if config.getString("type") == HiveSynchronizer.sinkName =>
          HiveSynchronizer.sync(config)
      }

      val finalPF =
        if (customTransform.isDefined)
          basePF.orElse(customTransform.get)
        else
          basePF

      finalPF.orElse({
        case config: Config => throw new InvalidInputException(s"Unsupported sink ${config.getString("type")}")
      })
    }

    val sinkPlugins = PluginLoader.loadSinkPlugins(config).map(getPartialFunction)

    if (sinkPlugins.isEmpty)
      getTransformFunc(None)
    else
      getTransformFunc(Some(sinkPlugins.reduce(_ orElse _)))
  }

  def run(dataFrame: DataFrame, keyField: String): Unit = {
    val matchRules = loadPlugins()

    sinkList.map(config => matchRules(config)).foreach(func => {
      try {
        func(dataFrame, keyField)
      } catch {
        case t: Throwable =>
          println(s"Failed to synchronize data, due to ${t.toString}")
      }
    })
  }
}
