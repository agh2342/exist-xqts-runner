/*
 * Copyright (C) 2018  The eXist Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.exist.xqts.runner

import java.io._
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.util.Properties

import org.exist.source.{Source, StringSource}
import org.exist.storage.DBBroker
import org.exist.test.ExistEmbeddedServer
import org.exist.util.serializer.XQuerySerializer
import org.exist.xquery.{CompiledXQuery, XQueryContext}

import scala.util.{Failure, Success, Try}
import scalaz.{-\/, \/, \/-}
import scalaz.syntax.either._
import scalaz.syntax.std.either._
import ExistServer._
import cats.effect.{IO, Resource}
import com.evolvedbinary.j8fu.function.{QuadFunctionE, TriFunctionE}
import javax.xml.parsers.SAXParserFactory
import javax.xml.transform.OutputKeys
import org.exist.Namespaces
import org.exist.dom.memtree.{DocumentImpl, SAXAdapter}
import org.exist.storage.txn.Txn
import org.exist.util.io.FastByteArrayInputStream
import org.exist.xquery.XPathException
import org.exist.xquery.value._
import org.xml.sax.InputSource

object ExistServer {
  type ExecutionTime = Long
  type CompilationTime = Long

  case class ExistServerException(t: Throwable, compilationTime: CompilationTime = 0, executionTime: ExecutionTime = 0) extends Exception(t)

  object Result {
    def apply(queryError: QueryError, compilationTime: CompilationTime, executionTime: ExecutionTime) = new Result(queryError.left, compilationTime, executionTime)
    def apply(queryResult: QueryResult, compilationTime: CompilationTime, executionTime: ExecutionTime) = new Result(queryResult.right, compilationTime, executionTime)
  }
  case class Result(result: QueryError \/ QueryResult, compilationTime: CompilationTime, executionTime: ExecutionTime)

  type QueryResult = Sequence
  object QueryError {
    def apply(xpathException: XPathException) = new QueryError(xpathException.getErrorCode.getErrorQName.getLocalPart, xpathException.getMessage)
  }
  case class QueryError(errorCode: String, message: String)

  /**
    * Starts up an eXist-db server.
    *
    * @return A reference to the server, or an exception.
    */
  def start(): Throwable \/ ExistServer = {
    val server = new ExistServer
    server.startServer() match {
      case Success(_) =>
        server.right
      case Failure(e) =>
        e.left
    }
  }
}

/**
  * Encapsulation of operations for an exist-db server.
  */
class ExistServer {
  private lazy val existServer = new ExistEmbeddedServer(true, true)

  /**
    * Starts the eXist-db server.
    *
    * @return Success or Failure.
    */
  private def startServer() : Try[Unit] = Try(existServer.startDb())

  /**
    * Get a connection to the eXist-db server.
    */
  def getConnection() : ExistConnection = {
    ExistConnection(existServer.getBrokerPool.getBroker)
  }

  /**
    * Shutdown the eXist-db server.
    */
  def stopServer() {
    existServer.stopDb()
  }
}

private object ExistConnection {
  def apply(broker: DBBroker) = new ExistConnection(broker)

  private val saxParserFactory = SAXParserFactory.newInstance()
  saxParserFactory.setNamespaceAware(true)
}

/**
  * Represents a connection
  * to an eXist-db server, i.e. a {@link org.exist.storage.DBBroker}
  *
  * @param broker the eXist-db broker to wrap.
  */
class ExistConnection(broker: DBBroker) extends AutoCloseable {
  private val xqueryPool = broker.getBrokerPool.getXQueryPool
  private val xquery = broker.getBrokerPool.getXQueryService

  /**
    * Execute an XQuery with eXist-db.
    *
    * @param query The XQuery to execute.
    * @param cacheCompiled true if you want to cache the compiled query form.
    * @param staticBaseUri An optional static-baseURI for the XQuery.
    * @param contextSequence An optional context sequence for the XQuery to operate on.
    * @param availableDocuments Any dynamically available Documents that should be available to the XQuery.
    * @param availableCollections Any dynamically available Collections that should be available to the XQuery.
    * @param availableTextResources Any dynamically available Text Resources that should be available to the XQuery.
    * @param externalVariables Any external variables that should be bound for the XQuery.
    *
    * @return the result or executing the query, or an exception.
    */
  def executeQuery(query: String, cacheCompiled: Boolean, staticBaseUri: Option[String], contextSequence: Option[Sequence], availableDocuments: Seq[(String, DocumentImpl)] = Seq.empty, availableCollections: Seq[(String, List[DocumentImpl])] = Seq.empty, availableTextResources: Seq[(String, Charset, String)] = Seq.empty, externalVariables: Seq[(String, Sequence)] = Seq.empty) : ExistServerException \/ Result = {

    /**
      * Sets up the XQuery Context.
      *
      * @param context The XQuery Context to configure
      */
    def setupContext(context: XQueryContext) {
      // set dynamically available documents
      type DocumentSupplier = TriFunctionE[DBBroker, Txn, String, com.evolvedbinary.j8fu.Either[DocumentImpl, org.exist.dom.persistent.DocumentImpl], XPathException]
      for ((uri, doc) <- availableDocuments) {
        val supplier: DocumentSupplier = (_, _, _) => com.evolvedbinary.j8fu.Either.Left(doc)
        context.addDynamicallyAvailableDocument(uri, supplier)
      }

      // set dynamically available collections
      type CollectionSupplier = TriFunctionE[DBBroker, Txn, String, Sequence, XPathException]
      for ((uri, docs) <- availableCollections) {
        val sequence = new ValueSequence()
        docs.map(sequence.add)
        val supplier: CollectionSupplier = (_, _, _) => sequence
        context.addDynamicallyAvailableCollection(uri, supplier)
      }

      // set dynamically available text resources
      type TextResourceSupplier = QuadFunctionE[DBBroker, Txn, String, Charset, Reader, XPathException]
      for ((uri, charset, text) <- availableTextResources) {
        val supplier: TextResourceSupplier = (_, _, _, _) => new StringReader(text)
        context.addDynamicallyAvailableTextResource(uri, charset, supplier)
      }

      // set the static base uri
      staticBaseUri.map(baseUri => context.setBaseURI(new AnyURIValue(baseUri)))

      // bind external variables
      for ((name, value) <- externalVariables) {
        context.declareVariable(name, value)
      }
    }

    /**
      * Get's a compiled XQuery from an XQuery source.
      *
      * Handles caching of compiled XQuery:
      *
      *   1. If the cache should be used, then it will try
      *   and retrieve a compiled version. If there is no
      *   compiled version, the query source will be
      *   compiled and added to the cache, before being
      *   returned.
      *
      *   2. If the cache should not be used, then the
      *   query source will be compiled before being
      *   returned.
      *
      * @param source The XQuery source to compile.
      *
      * @return A tuple of: the compiled XQuery,
      *         the XQuery context, and the time taken
      *         to compile the XQuery.
      */
    def getCompiledQuery(source: Source) : (CompiledXQuery, XQueryContext, CompilationTime) = {
      val startTime = System.currentTimeMillis()
      Option(cacheCompiled)
          .filter(b => b)
          .flatMap(_ => Option(xqueryPool.borrowCompiledXQuery(broker, source)))
        match {
          case Some(compiled) =>
            val context = compiled.getContext
            setupContext(compiled.getContext)
            (compiled, context, System.currentTimeMillis() - startTime)

          case None =>
            val context = new XQueryContext(broker.getBrokerPool())
            setupContext(context)
            val compiled = xquery.compile(broker, context, source)
            (compiled, context, System.currentTimeMillis() - startTime)
        }
    }

    /**
      * Handler to manage an XPathException
      * differently from any other type of throwable.
      *
      * XPathException will be converted to a {@link Result}
      * of {@link QueryError}, wilst any other exception
      * will be converted to an {@link ExistServerException}.
      *
      * @param t the exception.
      * @param compilationTime the time taken to compile the XQuery.
      * @param executionTime the time taken to execute the XQuery.
      *
      * @return either a {@link Result}, or a {@link ExistServerException}.
      */
    def fromExecutionException(t: Throwable, compilationTime: CompilationTime, executionTime: ExecutionTime) : ExistServerException \/ Result = {
      if (t.isInstanceOf[XPathException]) {
        Result(QueryError(t.asInstanceOf[XPathException]), compilationTime, executionTime).right
      } else {
        ExistServerException(t, compilationTime, executionTime).left
      }
    }

    val source = new StringSource(query)

    // compile step
    val compiledQueryIO : Resource[IO, (CompiledXQuery, XQueryContext, CompilationTime)] = Resource.make(IO {
      getCompiledQuery(source)
    })(compiledContext => IO {
      compiledContext._2.runCleanupTasks()
      if(cacheCompiled) {
        xqueryPool.returnCompiledXQuery(source, compiledContext._1)
      }
    })

    // execute step
    val executeQueryIO : IO[ExistServerException \/ Result] = compiledQueryIO.use {
      case (compiled, context, compilationTime) =>
          IO { System.currentTimeMillis() }
            .flatMap { startTime =>
              IO {
                xquery.execute(broker, compiled, contextSequence.getOrElse(null))
              }
                .flatMap(sequence => IO { Result(sequence, compilationTime, System.currentTimeMillis() - startTime).right })
                .handleErrorWith(throwable => IO { fromExecutionException(throwable, compilationTime,  System.currentTimeMillis() - startTime) })
        }
    }.handleErrorWith(throwable => IO { fromExecutionException(throwable, 0, 0) })  // 0 because an error here was caused by compilation, so  was no execution

    // run compilation and execution
    val queryResult = executeQueryIO.unsafeRunSync()
    queryResult
  }

  // TODO(AR) should return Throwable \/ String type
  /**
    * Serializes a Sequence to a String
    * using Adaptive serialization.
    *
    * @param sequence the sequence to serialize.
    *
    * @return the result of serializing the sequence.
    */
  def sequenceToStringAdaptive(sequence: Sequence) : String = {
    val outputProperties = new Properties()
    outputProperties.setProperty(OutputKeys.METHOD, "adaptive") // improves the output for expected value messages
    outputProperties.setProperty(OutputKeys.INDENT, "no")
    sequenceToString(sequence, outputProperties)
  }

  // TODO(AR) should return Throwable \/ String type
  /**
    * Serializes a Sequence to a String
    * using XML serialization.
    *
    * @param sequence the sequence to serialize.
    *
    * @return the result of serializing the sequence.
    */
  def sequenceToString(sequence: Sequence) : String = {
    sequenceToString(sequence, new Properties())
  }

  // TODO(AR) should return Throwable \/ String type
  /**
    * Serializes a Sequence to a String.
    *
    * If the serialization method is not
    * set in the properties, the the XML
    * method will be used.
    *
    * @param sequence the sequence to serialize.
    * @param outputProperties the serialization settings.
    *
    * @return the result of serializing the sequence.
    */
  def sequenceToString(sequence: Sequence, outputProperties: Properties): String = {
    val writerIO = Resource.make(IO { new StringWriter() })(writer => IO { writer.close() })

    val serializationIO = writerIO.use(writer => IO {
      val serializer = new XQuerySerializer(broker, outputProperties, writer)
      serializer.serialize(sequence)
      writer.getBuffer.toString
        .replace("\r", "").replace("\n", ", ")  // further improves the output for expected value messages
    })

    serializationIO.unsafeRunSync()
  }

  /**
    * Parses a String representation of an XML Document
    * to an in-memory DOM.
    *
    * @param xml the string of xml to parse.
    *
    * @return either the Document object, or an exception.
    */
  def parseXml(xml: Array[Byte]): ExistServerException \/ DocumentImpl = {
    val xmlIO = Resource.make(IO { new FastByteArrayInputStream(xml) })(is => IO { is.close() })

    val parseIO = xmlIO.use(is => IO {
      val saxAdapter = new SAXAdapter()
      val saxParser = ExistConnection.saxParserFactory.newSAXParser()
      val xmlReader = saxParser.getXMLReader()

      xmlReader.setContentHandler(saxAdapter)
      xmlReader.setProperty(Namespaces.SAX_LEXICAL_HANDLER, saxAdapter)
      xmlReader.parse(new InputSource(is))

      saxAdapter.getDocument
    })

    parseIO
      .attempt
      .map(_.disjunction.leftMap(ExistServerException(_)))
      .unsafeRunSync()
  }

  /**
    * Closes the connection
    * to the eXist-db server.
    */
  override def close(): Unit = broker.close()
}
