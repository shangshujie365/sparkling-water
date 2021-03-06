/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.apache.spark.repl.h2o

import org.apache.spark.repl.{Main, SparkIMain}
import org.apache.spark.util.MutableURLClassLoader
import org.apache.spark.{HttpServer, SparkContext, SparkEnv}

import scala.collection.mutable
import scala.reflect.io.PlainFile
import scala.tools.nsc.Settings

/**
  * SparkIMain allowing multiple interpreters to coexist in parallel. Each line in repl is wrapped in a package which
  * name contains current session id
  */
private[repl] class H2OIMain private(initialSettings: Settings,
               interpreterWriter: IntpResponseWriter,
               val sessionID: Int,
               propagateExceptions: Boolean = false) extends SparkIMain(initialSettings, interpreterWriter, propagateExceptions){

  setupCompiler()
  stopClassServer()
  setupClassNames()

  /**
    * Stop class server started in SparkIMain constructor because we have to use our class server which is already
    * running
    */
  private def stopClassServer() = {
    val fieldClassServer = this.getClass.getSuperclass.getDeclaredField("classServer")
    fieldClassServer.setAccessible(true)
    val classServer = fieldClassServer.get(this).asInstanceOf[HttpServer]
    classServer.stop()
  }

  /**
    * Create a new instance of compiler with the desired output directory
    */
  private def setupCompiler() = {
    // set virtualDirectory to our shared directory for all repl instances
    val fieldVirtualDirectory = this.getClass.getSuperclass.getDeclaredField("org$apache$spark$repl$SparkIMain$$virtualDirectory")
    fieldVirtualDirectory.setAccessible(true)
    fieldVirtualDirectory.set(this,new PlainFile(H2OInterpreter.classOutputDir))

    // initialize the compiler again with new virtualDirectory set
    val fieldCompiler = this.getClass.getSuperclass.getDeclaredField("_compiler")
    fieldCompiler.setAccessible(true)
    fieldCompiler.set(this,newCompiler(settings,reporter))
  }

  /**
    * Ensure that each class defined in repl is in a package containing number of repl session
    */
  private def setupClassNames() = {
    import naming._
    // sessionNames is lazy val and needs to be accessed first in order to be then set again to our desired value
    naming.sessionNames.line
    val fieldSessionNames = naming.getClass.getDeclaredField("sessionNames")
    fieldSessionNames.setAccessible(true)
    fieldSessionNames.set(naming, new SessionNames {
      override def line  = "intp_id_" + sessionID + "." + propOr("line")
    })
  }

}

object H2OIMain {

  val existingInterpreters = mutable.HashMap.empty[Int, H2OIMain]
  private var interpreterClassloader: InterpreterClassLoader = _
  private var _initialized = false

  private def setClassLoaderToSerializers(classLoader: ClassLoader): Unit = {
    SparkEnv.get.serializer.setDefaultClassLoader(classLoader)
    SparkEnv.get.closureSerializer.setDefaultClassLoader(classLoader)
  }

  /**
    * Add directory with classes defined in repl to the classloader
    * which is used in the local mode. This classloader is obtained using reflections.
    */
  private def prepareLocalClassLoader() = {
    val f = SparkEnv.get.serializer.getClass.getSuperclass.getDeclaredField("defaultClassLoader")
    f.setAccessible(true)
    val value = f.get(SparkEnv.get.serializer)
    value match {
      case v: Option[_] => {
        v.get match {
          case cl: MutableURLClassLoader => cl.addURL(H2OInterpreter.classOutputDir.toURI.toURL)
          case _ =>
        }
      }
      case _ =>
    }
  }

  private def initialize(sc: SparkContext): Unit = {
    if (sc.isLocal) {
      // master set to local or local[*]
      prepareLocalClassLoader()
      interpreterClassloader = new InterpreterClassLoader()
    } else {
      if (Main.interp != null) {
        interpreterClassloader = new InterpreterClassLoader(Main.interp.intp.classLoader)
      } else {
        // non local mode, application not started using SparkSubmit
        interpreterClassloader = new InterpreterClassLoader()
      }
    }
    setClassLoaderToSerializers(interpreterClassloader)
  }
  def getInterpreterClassloader: InterpreterClassLoader = {
    interpreterClassloader
  }

  def createInterpreter(sc: SparkContext, settings: Settings, interpreterWriter: IntpResponseWriter, sessionId: Int): H2OIMain = synchronized {
    if(!_initialized){
      initialize(sc)
      _initialized = true
    }
    existingInterpreters += (sessionId -> new H2OIMain(settings, interpreterWriter, sessionId, false))
    existingInterpreters(sessionId)
  }
}
