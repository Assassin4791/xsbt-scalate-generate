package com.mojolly.scalate

import sbt._
import Keys._
import Project.Initialize
import java.io.File
import sbt.classpath.ClasspathUtilities

object ScalatePlugin extends Plugin {

  case class Binding(
          name: String,
          className: String = "Any",
          importMembers: Boolean = false,
          defaultValue: String = "",
          kind: String = "val",
          isImplicit: Boolean = false)

  /**
   * Template Configuration
   * @param scalateTemplateDirectory
   * @param scalateImports
   * @param scalateBindings
   */
  case class TemplateConfig(
     scalateTemplateDirectory:File,
     scalateImports:Seq[String],
     scalateBindings:Seq[Binding],
     packagePrefix: Option[String] = Some("scalate")
   )

  val Scalate = config("scalate") hide

  object ScalateKeys {

    val scalateTemplateConfig = SettingKey[Seq[TemplateConfig]]("scalate-template-configuration",
      "Different Template Configurations")

    val scalateLoggingConfig = SettingKey[File]("scalate-logging-config",
      "Logback config to get rid of that infernal debug output.")

     val scalateOverwrite = SettingKey[Boolean]("scalate-overwrite",
      "Always generate the Scala sources even when they haven't changed")

    val scalateClasspaths = TaskKey[ScalateClasspaths]("scalate-classpaths")
  }

  import ScalateKeys._
    
  def scalateSourceGeneratorTask: Initialize[Task[Seq[File]]] = {
    (streams, sourceManaged in Compile, scalateLoggingConfig in Compile, managedClasspath in scalateClasspaths, scalateOverwrite in Compile, scalateTemplateConfig in Compile) map {
      (out, outputDir, logConfig, cp, overwrite, tc) => generateScalateSource(out, new File(outputDir, "scalate"), logConfig, cp, overwrite, tc)
    }
  }

  type Generator = {
    var packagePrefix: String
    var sources: File
    var targetDirectory: File
    var logConfig: File
    var overwrite: Boolean
    var scalateImports: Array[String]
    var scalateBindings: Array[Array[AnyRef]]
    def execute: Array[File]
  }
  
  final case class ScalateClasspaths(classpath: PathFinder, scalateClasspath: PathFinder)

  def scalateClasspathsTask(cp: Classpath, scalateCp: Classpath) = ScalateClasspaths(cp.map(_.data), scalateCp.map(_.data))

  def generateScalateSource(out: TaskStreams, outputDir: File, logConfig: File, cp: Classpath, overwrite: Boolean, templates:Seq[TemplateConfig]) = {
    withScalateClassLoader(cp.files) { classLoader =>
      templates flatMap { t =>

        val className = "com.mojolly.scalate.Generator"
        val klass = classLoader.loadClass(className)
        val inst = klass.newInstance
        val generator = klass.newInstance.asInstanceOf[Generator]

        val source = t.scalateTemplateDirectory
        out.log.info("Compiling Templates in Template Directory: %s" format t.scalateTemplateDirectory.getAbsolutePath)

        val preservedLogbackConfiguration = Option(System.getProperty("logback.configurationFile"))
        val targetDirectory = outputDir / source.getName
        // Because we have to Scope each Template Folder we need to create unique package names
        generator.packagePrefix = t.packagePrefix getOrElse source.getName
        generator.sources = source
        generator.targetDirectory = targetDirectory
        generator.logConfig = logConfig
        generator.overwrite = overwrite
        generator.scalateImports = t.scalateImports.toArray
        generator.scalateBindings = t.scalateBindings.toArray map { b =>
          Array(
            b.name.asInstanceOf[AnyRef],
            b.className.asInstanceOf[AnyRef],
            b.importMembers.asInstanceOf[AnyRef],
            b.defaultValue.asInstanceOf[AnyRef],
            b.kind.asInstanceOf[AnyRef],
            b.isImplicit.asInstanceOf[AnyRef])

        }
        try {
          generator.execute.toList
        } finally {
          preservedLogbackConfiguration match {
            case Some(oldConfig) => System.setProperty("logback.configurationFile", oldConfig)
            case None => System.clearProperty("logback.configurationFile")
          }
        }
      }
    }
  }

  val scalateSettings: Seq[sbt.Project.Setting[_]] = Seq(
    ivyConfigurations += Scalate,
    scalateTemplateConfig in Compile := Seq(TemplateConfig(file(".") / "src" / "main" / "webapp" / "WEB-INF", Nil, Nil, Some("scalate"))),
    scalateLoggingConfig in Compile <<= (resourceDirectory in Compile) { _ / "logback.xml" },
    libraryDependencies += "com.mojolly.scalate" %% "scalate-generator" % Version.version % Scalate.name,
    sourceGenerators in Compile <+= scalateSourceGeneratorTask,
    watchSources <++= (scalateTemplateConfig in Compile) map ( _.map(_.scalateTemplateDirectory).flatMap(d => (d ** "*").get)),
    scalateOverwrite := true,
    managedClasspath in scalateClasspaths <<= (classpathTypes, update) map { ( ct, report)   =>
      Classpaths.managedJars(Scalate, ct, report)
    },
    scalateClasspaths <<= (fullClasspath in Runtime, managedClasspath in scalateClasspaths) map scalateClasspathsTask)

  /**
   * Runs a block of code with the Scalate classpath as the context class
   * loader.  The Scalate classpath is the [[runClassPath]] plus the
   * [[buildScalaInstance]]'s jars.
   */
  protected def withScalateClassLoader[A](runClassPath: Seq[File])(f: ClassLoader => A): A = {
    val oldLoader = Thread.currentThread.getContextClassLoader
    val loader = ClasspathUtilities.toLoader(runClassPath)
    Thread.currentThread.setContextClassLoader(loader)
    try {
      f(loader)
    } finally {
      Thread.currentThread.setContextClassLoader(oldLoader)
    }
  }

}
