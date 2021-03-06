/**
 * Copyright (C) 2017 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.knaw.dans.easy.solr4files

import java.net.URLDecoder

import nl.knaw.dans.easy.solr4files.SearchServlet.userFilters
import nl.knaw.dans.easy.solr4files.components.User
import nl.knaw.dans.lib.error._
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import org.apache.http.HttpStatus._
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.util.ClientUtils.escapeQueryChars
import org.scalatra._
import org.scalatra.auth.strategy.BasicAuthStrategy.BasicAuthRequest

import scala.util.{ Failure, Success, Try }
import scalaj.http.HttpResponse

class SearchServlet(app: EasySolr4filesIndexApp) extends ScalatraServlet with DebugEnhancedLogging {
  logger.info("File index Servlet running...")

  private def respond(result: Try[String]): ActionResult = {
    result.map(Ok(_))
      .doIfFailure { case e => logger.error(e.getMessage, e) }
      .getOrRecover {
        case SolrBadRequestException(message, _) => BadRequest(message)
        case HttpStatusException(message, r: HttpResponse[String]) if r.code == SC_NOT_FOUND => NotFound(message)
        case HttpStatusException(message, r: HttpResponse[String]) if r.code == SC_SERVICE_UNAVAILABLE => ServiceUnavailable(message)
        case HttpStatusException(message, r: HttpResponse[String]) if r.code == SC_REQUEST_TIMEOUT => RequestTimeout(message)
        case _ => InternalServerError()
      }
  }

  get("/") {
    logger.info(s"file search request: $params")
    contentType = "application/json"

    val fetchFields = Seq("easy_dataset_*", "easy_file_*") // TODO params.get("???") but what is possible allowed?
    val fetchExceptions = Seq("easy_dataset_depositor_id")

    // no command line equivalent, use http://localhost:8983/solr/#/fileitems/query
    // or for example:           curl 'http://localhost:8983/solr/fileitems/query?q=*'
    val result = app.authenticate(new BasicAuthRequest(request)) match {
      case (Success(user)) => respond(app.search(createQuery(user, fetchFields), fetchExceptions))
      case (Failure(InvalidUserPasswordException(_, _))) => Unauthorized()
      case (Failure(AuthenticationNotAvailableException(_))) => ServiceUnavailable("Authentication service not available, try anonymous search")
      case (Failure(AuthenticationTypeNotSupportedException(_))) => BadRequest("Only anonymous search or basic authentication supported")
      case (Failure(t)) =>
        logger.error(s"not expected exception", t)
        InternalServerError("not expected exception")
    }
    logger.info(s"file search returned ${ response.status.line } for $params")
    result
  }

  /**
   * @return the URI params of the request translated into a Solr search request
   */
  private def createQuery(user: Option[User], fetch: Seq[String]) = {
    // invalid optional values are replaced by the default value
    val rows = params.get("limit").withFilter(_.matches("[1-9][0-9]*")).map(_.toInt).getOrElse(10)
    val start = params.get("skip").withFilter(_.matches("[0-9]+")).map(_.toInt).getOrElse(0)
    new SolrQuery() {
      params.get("text") match {
        case None => setQuery("*:*")
        case Some(t) => setQuery(t)
          // injection: https://packetstormsecurity.com/files/144678/apachesolr701-xxe.txt
          // https://lucene.apache.org/solr/guide/6_6/query-syntax-and-parsing.html
          // lucene seems not safe: Support for using any type of query parser as a nested clause
          // edismax: supports the full Lucene query parser syntax, so possibly not safe too
          // dismax: more like [...] Google [...] makes [...] appropriate [...] for many consumer applications
          set("defType", "dismax")
      }
      accessibilityFilters(user)
        .foreach(q => addFilterQuery(q))
      fetch.foreach(addField)
      userSpecifiedFilters()
        .withFilter(_.isDefined)
        .foreach(fqOpt => addFilterQuery(fqOpt.get))
      setStart(start)
      setRows(rows) // todo max from application.properties
      setTimeAllowed(5000) // 5 seconds TODO configurable in application.properties
      // setFacet... setMoreLike... setHighlight... setDebug... etc

      logger.info(s"$user requested: " + params.asString)
      logger.info("request passed on as: " + toString)
      logger.info("decoded: " + URLDecoder.decode(toString, "UTF8"))
    }
  }

  private def accessibilityFilters(user: Option[User]): Seq[String] = {
    val toAnonymous = "easy_file_accessible_to:ANONYMOUS"
    val toKnown = "easy_file_accessible_to:KNOWN"
    val available = "easy_dataset_date_available:[* TO NOW]"
    user match {
      case None => Seq(s"$toAnonymous", available)
      case Some(User(id, _)) =>
        // TODO reuse cache of partial filters
        val own = "easy_dataset_depositor_id:" + id
        Seq(
          s"$toAnonymous OR $toKnown OR $own",
          s"$available OR $own"
        )
      // TODO add message to header (you can see ... because you are the owner) like in the webui?
    }
  }

  private def userSpecifiedFilters(): Seq[Option[String]] = {

    userFilters.map(key =>
      multiParams
        .get(key)
        .map(createClauseChain(key, _))
    )
  }

  private def createClauseChain(key: String, values: Seq[String]) = {
    values
      .map(createClause(key, _))
      .mkString(" OR ")
  }

  private def createClause(key: String, value: String) = {
    s"easy_$key:${ escapeQueryChars(value) }"
  }
}

object SearchServlet {

  // TODO keep in sync with literals returned by DDM, Bag and FileItem classes
  val userFilters = Seq(
    "dataset_id",
    "dataset_doi",
    "dataset_depositor_id",
    "file_mime_type",
    "file_size",
    "file_checksum",
    "dataset_title",
    "dataset_creator",
    "dataset_audience",
    "dataset_relation",
    "dataset_subject",
    "dataset_coverage"
  )
}
