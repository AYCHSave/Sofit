/**
Open Bank Project - Transparency / Social Finance Web Application
Copyright (C) 2011, 2012, TESOBE / Music Pictures Ltd

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.

Email: contact@tesobe.com
TESOBE / Music Pictures Ltd
Osloerstrasse 16/17
Berlin 13359, Germany

  This product includes software developed at
  TESOBE (http://www.tesobe.com/)
  by
  Simon Redfern : simon AT tesobe DOT com
  Stefan Bethge : stefan AT tesobe DOT com
  Everett Sochowski : everett AT tesobe DOT com
  Ayoub Benali: ayoub AT tesobe DOT com

 */
package code.api

import code.actors.EnvelopeInserter
import net.liftweb.http._
import net.liftweb.http.rest._
import net.liftweb.json.JsonDSL._
import net.liftweb.json.Printer._
import net.liftweb.json.Extraction._
import net.liftweb.json.JsonAST._
import java.util.Calendar
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.common.Empty
import net.liftweb.mongodb._
import net.liftweb.json.JsonAST.JString
import com.mongodb.casbah.Imports._
import _root_.java.math.MathContext
import org.bson.types._
import org.joda.time.{ DateTime, DateTimeZone }
import java.util.regex.Pattern
import _root_.net.liftweb.common._
import _root_.net.liftweb.util._
import _root_.net.liftweb.http._
import _root_.net.liftweb.mapper._
import _root_.net.liftweb.util.Helpers._
import _root_.net.liftweb.sitemap._
import _root_.scala.xml._
import _root_.net.liftweb.http.S._
import _root_.net.liftweb.http.RequestVar
import _root_.net.liftweb.util.Helpers._
import _root_.net.liftweb.common.Full
import net.liftweb.mongodb.{ Skip, Limit }
import _root_.net.liftweb.http.S._
import _root_.net.liftweb.mapper.view._
import com.mongodb._
import code.model.dataAccess.{ Account, OBPEnvelope, OBPUser,APIMetric }
import code.model.dataAccess.HostedAccount
import code.model.dataAccess.LocalStorage
import code.model.traits.ModeratedTransaction
import code.model.traits.View
import code.model.implementedTraits.View
import code.model.dataAccess.OBPEnvelope._
import code.model.traits.BankAccount
import code.model.implementedTraits.Public
import code.model.traits.Bank
import code.model.traits.User
import java.util.Date
import code.api.OAuthHandshake._
import code.model.traits.ModeratedBankAccount
import net.liftweb.util.Helpers.now
import net.liftweb.json.Extraction
import _root_.net.liftweb.json.Serialization
import net.liftweb.json.NoTypeHints

  case class APICallAmount(
      url: String,
      amount: Int
    )
  case class APICallAmounts(
      stats : List[APICallAmount]
    )


  case class APICallsForDay(
      amount : Int,
      date : Date
    )
  case class APICallsPerDay(
      stats : List[APICallsForDay]
    )

object OBPAPI1_0 extends RestHelper with Loggable {

  implicit val _formats = Serialization.formats(NoTypeHints)

  val dateFormat = ModeratedTransaction.dateFormat

  private def getOBPUser(httpCode : Int, tokenID : Box[String]) : Box[OBPUser] =
  if(httpCode==200)
  {
    import code.model.Token
    Token.find(By(Token.key, tokenID.get)) match {
      case Full(token) => tryo{
          token.userId.get.toLong
        } match {
          case Full(id) => OBPUser.find(By(OBPUser.id, id))
          case _ => Empty
      }
      case _ => Empty
    }
  }
  else
    Empty

  private def logAPICall =
    APIMetric.createRecord.
      url(S.uriAndQueryString.getOrElse("")).
      date((now: TimeSpan)).
      save

  serve("obp" / "v1.0" prefix {

    case Nil JsonGet json => {
      //log the API call
      logAPICall

      def gitCommit : String = {
        val commit = tryo{
          val properties = new java.util.Properties()
          properties.load(getClass().getClassLoader().getResourceAsStream("git.properties"))
          properties.getProperty("git.commit.id", "")
        }
        commit getOrElse ""
      }

      val apiDetails = {
        ("api" ->
          ("version" -> "1.0") ~
          ("git_commit" -> gitCommit) ~
          ("hosted_by" ->
            ("organisation" -> "TESOBE") ~
            ("email" -> "contact@tesobe.com") ~
            ("phone" -> "+49 (0)30 8145 3994"))) ~
        ("links" ->
          ("rel" -> "banks") ~
          ("href" -> "/banks") ~
          ("method" -> "GET") ~
          ("title" -> "Returns a list of banks supported on this server"))
      }

      JsonResponse(apiDetails)
    }

    case bankAlias :: "accounts" :: accountAlias :: "transactions" :: viewName :: Nil JsonGet json => {

      //log the API call
      logAPICall

      import code.api.OAuthHandshake._
      val (httpCode, data, oAuthParameters) = validator("protectedResource", "GET")
      val headers = ("Content-type" -> "application/x-www-form-urlencoded") :: Nil

      def asInt(s: Box[String], default: Int): Int = {
        s match {
          case Full(str) => tryo { str.toInt } getOrElse default
          case _ => default
        }
      }
      val limit = asInt(json.header("obp_limit"), 50)
      val offset = asInt(json.header("obp_offset"), 0)
      /**
       * sortBy is currently disabled as it would open up a security hole:
       *
       * sortBy as currently implemented will take in a parameter that searches on the mongo field names. The issue here
       * is that it will sort on the true value, and not the moderated output. So if a view is supposed to return an alias name
       * rather than the true value, but someone uses sortBy on the other bank account name/holder, not only will the returned data
       * have the wrong order, but information about the true account holder name will be exposed due to its position in the sorted order
       *
       * This applies to all fields that can have their data concealed... which in theory will eventually be most/all
       *
       */
      //val sortBy = json.header("obp_sort_by")
      val sortBy = None
      val sortDirection = OBPOrder(json.header("obp_sort_by"))
      val fromDate = tryo{dateFormat.parse(json.header("obp_from_date") getOrElse "")}.map(OBPFromDate(_))
      val toDate = tryo{dateFormat.parse(json.header("obp_to_date") getOrElse "")}.map(OBPToDate(_))

      def getTransactions(bankAccount: BankAccount, view: View, user: Option[OBPUser]) = {
        if(bankAccount.authorizedAccess(view, user)) {
          val basicParams = List(OBPLimit(limit),
                          OBPOffset(offset),
                          OBPOrdering(sortBy, sortDirection))

          val params : List[OBPQueryParam] = fromDate.toList ::: toDate.toList ::: basicParams
          bankAccount.getModeratedTransactions(params: _*)(view.moderate)
        } else Nil
      }

      val response = for {
        bankAccount <- BankAccount(bankAlias, accountAlias)
        view <- View.fromUrl(viewName) //TODO: This will have to change if we implement custom view names for different accounts
      } yield {
        val ts = getTransactions(bankAccount, view, getOBPUser(httpCode,oAuthParameters.get("oauth_token")))
        JsonResponse("transactions" -> ts.map(t => t.toJson(view)))
      }

      response getOrElse InMemoryResponse(data.getBytes, headers, Nil, 401) : LiftResponse
    }

    case bankAlias :: "accounts" :: accountAlias :: "transactions" ::
      transactionID :: "transaction" :: viewName :: Nil JsonGet  json => {

      //log the API call
      logAPICall

      val (httpCode, data, oAuthParameters) = validator("protectedResource", "GET")
      val user = getOBPUser(httpCode,oAuthParameters.get("oauth_token"))

      val moderatedTransactionAndView = for {
        bank <- Bank(bankAlias) ?~ { "bank "  + bankAlias + " not found"} ~> 404
        account <- BankAccount(bankAlias, accountAlias) ?~ { "account "  + accountAlias + " not found for bank"} ~> 404
        view <- View.fromUrl(viewName) ?~ { "view "  + viewName + " not found for account"} ~> 404
        moderatedTransaction <- account.moderatedTransaction(transactionID, view, user) ?~ "view/transaction not authorised" ~> 401
      } yield {
        (moderatedTransaction, view)
      }

      val links : List[JObject] = Nil

      moderatedTransactionAndView.map(mtAndView => JsonResponse(("transaction" -> mtAndView._1.toJson(mtAndView._2)) ~
                              ("links" -> links)))
    }

    case bankAlias :: "accounts" :: accountAlias :: "transactions" ::
      transactionID :: "comments" :: viewName :: Nil JsonGet json => {

      //log the API call
      logAPICall

      val (httpCode, data, oAuthParameters) = validator("protectedResource", "GET")
      val user = getOBPUser(httpCode,oAuthParameters.get("oauth_token"))

      val comments = for {
        bank <- Bank(bankAlias) ?~ { "bank "  + bankAlias + " not found"} ~> 404
        account <- BankAccount(bankAlias, accountAlias) ?~ { "account "  + accountAlias + " not found for bank"} ~> 404
        view <- View.fromUrl(viewName) ?~ { "view "  + viewName + " not found for account"} ~> 404
        moderatedTransaction <- account.moderatedTransaction(transactionID, view, user) ?~ "view/transaction not authorised" ~> 401
        comments <- Box(moderatedTransaction.metadata).flatMap(_.comments) ?~ "transaction metadata not authorised" ~> 401
      } yield comments

      val links : List[JObject] = Nil

        comments.map(cs => JsonResponse(("comments" -> cs.map(_.toJson)) ~
                        ("links" -> links)))
    }

    case bankPermalink :: "accounts" :: Nil JsonGet json => {

      //log the API call
      logAPICall

      val (httpCode, data, oAuthParameters) = validator("protectedResource", "GET")
      val headers = ("Content-type" -> "application/x-www-form-urlencoded") :: Nil
      val user = getOBPUser(httpCode,oAuthParameters.get("oauth_token"))

      def bankAccountSet2JsonResponse(bankAccounts: Set[BankAccount]): LiftResponse = {
        val accJson = bankAccounts.map(bAcc => bAcc.overviewJson(user))
        JsonResponse(("accounts" -> accJson))
      }

      Bank(bankPermalink) match {
        case Full(bank) =>
        {
          val availableAccounts = bank.accounts.filter(_.permittedViews(user).size!=0)
          if(availableAccounts.size!=0)
            bankAccountSet2JsonResponse(availableAccounts)
          else
            InMemoryResponse(data.getBytes, headers, Nil, httpCode)
        }
        case _ =>  {
          val error = "bank " + bankPermalink + " not found"
          InMemoryResponse(error.getBytes(), headers, Nil, 404)
        }
      }
    }

    case bankAlias :: "accounts" :: accountAlias :: "account" :: viewName :: Nil JsonGet json => {

      //log the API call
      logAPICall

      val (httpCode, data, oAuthParameters) = validator("protectedResource", "GET")
      val headers = ("Content-type" -> "application/x-www-form-urlencoded") :: Nil
      val user = getOBPUser(httpCode,oAuthParameters.get("oauth_token"))

      case class ModeratedAccountAndViews(account: ModeratedBankAccount, views: Set[View])

      val moderatedAccountAndViews = for {
        bank <- Bank(bankAlias) ?~ { "bank "  + bankAlias + " not found"} ~> 404
      account <- BankAccount(bankAlias, accountAlias) ?~ { "account "  + accountAlias + " not found for bank"} ~> 404
      view <- View.fromUrl(viewName) ?~ { "view "  + viewName + " not found for account"} ~> 404
      moderatedAccount <- account.moderatedBankAccount(view, user)  ?~ {"view/account not authorised"} ~> 401
      availableViews <- Full(account.permittedViews(user))
      } yield ModeratedAccountAndViews(moderatedAccount, availableViews)

      def linkJson(view: View): JObject = {
        ("rel" -> view.name) ~
        ("href" -> { "/" + bankAlias + "/accounts/" + accountAlias + "/transactions/" + view.permalink }) ~
        ("method" -> "GET") ~
        ("title" -> view.description)
      }

      def bankAccountMetaData(mv : ModeratedAccountAndViews) = {
        ("views_available" -> mv.views.map(_.toJson)) ~
        ("links" -> mv.views.map(linkJson))
      }

      moderatedAccountAndViews.map(mv => JsonResponse("account" -> mv.account.toJson ~ bankAccountMetaData(mv)))
    }

    case bankAlias :: "offices" :: Nil JsonGet json => {

      //log the API call
      logAPICall

      //TODO: An office model needs to be created
      val offices : List[JObject] = Nil
      JsonResponse("offices" -> offices)
    }

    case bankAlias :: "bank" :: Nil JsonGet json => {

      //log the API call
      logAPICall

      def links = {
        def accounts = {
          ("rel" -> "accounts") ~
          ("href" -> {"/" + bankAlias + "/accounts"}) ~
          ("method" -> "GET") ~
          ("title" -> "Get list of accounts available")
        }

        def offices = {
          ("rel" -> "offices") ~
          ("href" -> {"/" + bankAlias + "/offices"}) ~
          ("method" -> "GET") ~
          ("title" -> "Get list of offices")
        }

        List(accounts, offices)
      }

      val bank = for {
        bank <- Bank(bankAlias) ?~ { "bank " + bankAlias + " not found"} ~> 404
      } yield bank

      bank.map(b => JsonResponse(b.detailedJson ~ ("links" -> links)))
    }

    case "banks" :: Nil JsonGet json => {

      //log the API call
      logAPICall

      JsonResponse("banks" -> Bank.toJson(Bank.all))
    }
  })

  // metrics API calls

  serve("obp" / "v1.0" / "metrics" prefix {
    case "demo-bar" :: Nil JsonGet json => {
      def byURL(metric : APIMetric) : String =
        metric.url.get

      def byUsage(x : APICallAmount, y : APICallAmount) =
        x.amount > y.amount

      val results = APICallAmounts(APIMetric.findAll.groupBy[String](byURL).toSeq.map(t => APICallAmount(t._1,t._2.length)).toList.sort(byUsage))

      JsonResponse(Extraction.decompose(results))
    }

    case "demo-line" :: Nil JsonGet json => {

      def byDay(metric  : APIMetric) : Date = {
        val metricDate = metric.date.get
        val cal = Calendar.getInstance()
        cal.setTime(metricDate)
        cal.set(Calendar.HOUR,0)
        cal.set(Calendar.MINUTE,0)
        cal.set(Calendar.SECOND,0)
        cal.set(Calendar.MILLISECOND,0)
        cal.getTime
       }

      def byOldestDate(x : APICallsForDay, y :  APICallsForDay) : Boolean =
        x.date before y.date

      val results  = APICallsPerDay(APIMetric.findAll.groupBy[Date](byDay).toSeq.map(t => APICallsForDay(t._2.length,t._1)).toList.sort(byOldestDate))
      JsonResponse(Extraction.decompose(results))
    }

  })
}