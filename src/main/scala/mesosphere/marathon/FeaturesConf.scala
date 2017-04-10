package mesosphere.marathon

import org.rogach.scallop.{ ScallopConf, ScallopOption }

trait FeaturesConf extends ScallopConf {

  /**
    * Indicates the http backend to use
    */
  lazy val httpServiceBackend: ScallopOption[String] = opt[String](
    "http_service_backend",
    descr = "The HTTP service backend to use",
    validate = Set("jetty", "akkahttp").contains,
    default = Some("jetty"),
    required = false,
    noshort = true,
    hidden = true
  )
}
