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

import net.liftweb.http._
import net.liftweb.http.rest._
import net.liftweb.json.JsonDSL._
import net.liftweb.json.Printer._
import net.liftweb.json.Extraction
import net.liftweb.json.JsonAST._
import net.liftweb.common.{Failure,Full,Empty, Box, Loggable}
import net.liftweb.mongodb._
import com.mongodb.casbah.Imports._
import _root_.java.math.MathContext
import org.bson.types._
import org.joda.time.{ DateTime, DateTimeZone }
import java.util.regex.Pattern
import _root_.net.liftweb.util._
import _root_.net.liftweb.mapper._
import _root_.net.liftweb.util.Helpers._
import _root_.net.liftweb.sitemap._
import _root_.scala.xml._
import _root_.net.liftweb.http.S._
import _root_.net.liftweb.http.RequestVar
import _root_.net.liftweb.util.Helpers._
import net.liftweb.mongodb.{ Skip, Limit }
import _root_.net.liftweb.http.S._
import _root_.net.liftweb.mapper.view._
import com.mongodb._
import code.model.traits._
import code.model.implementedTraits.View
import code.model.implementedTraits.Public
import java.util.Date
import code.api.OAuthHandshake._
import code.model.dataAccess.APIMetric
import code.model.dataAccess.OBPEnvelope.{OBPOrder, OBPLimit, OBPOffset, OBPOrdering, OBPFromDate, OBPToDate, OBPQueryParam}
import java.net.URL

case class TagJSON(
  value : String,
  posted_date : Date
)

case class NarrativeJSON(
  narrative : String
)

case class CommentJSON(
  value : String,
  posted_date : Date
)

case class ImageJSON(
  URL : String,
  label : String
)
case class MoreInfoJSON(
  more_info : String
)
case class UrlJSON(
  URL : String
)
case class ImageUrlJSON(
  image_URL : String
)
case class OpenCorporatesUrlJSON(
  open_corporates_url : String
)
case class WhereTagJSON(
  where : GeoCord
)
case class CorporateLocationJSON(
  corporate_location : GeoCord
)
case class GeoCord(
  longitude : Double,
  latitude : Double
)


object OBPAPI1_1 extends RestHelper with Loggable {

  implicit def errorToJson(error: ErrorMessage): JValue = Extraction.decompose(error)
  implicit def successToJson(success: SuccessMessage): JValue = Extraction.decompose(success)

  val dateFormat = ModeratedTransaction.dateFormat

  private def httpMethod : String =
    S.request match {
      case Full(r) => r.request.method
      case _ => "GET"
    }

  private def getUser(httpCode : Int, tokenID : Box[String]) : Box[User] =
  if(httpCode==200)
  {
    import code.model.Token
    logger.info("OAuth header correct ")
    Token.find(By(Token.key, tokenID.get)) match {
      case Full(token) => {
        logger.info("access token found")
        User.findById(token.userId.get)
      }
      case _ =>{
        logger.warn("no token " + tokenID.get + " found")
        Empty
      }
    }
  }
  else
    Empty

  private def isThereAnOAuthHeader : Boolean = {
    S.request match {
      case Full(a) =>  a.header("Authorization") match {
        case Full(parameters) => parameters.contains("OAuth")
        case _ => false
      }
      case _ => false
    }
  }

  private def logAPICall =
    APIMetric.createRecord.
      url(S.uriAndQueryString.getOrElse("")).
      date((now: TimeSpan)).
      save

  private def isFieldAlreadySet(field : String) : Box[String] =
    if(field.isEmpty)
     Full(field)
    else
      Failure("field already set, use PUT method to update it")

  private def transactionJson(t : ModeratedTransaction) : JObject = {
    ("transaction" ->
      ("uuid" -> t.uuid) ~
      ("id" -> t.id) ~
      ("this_account" -> t.bankAccount.map(thisAccountJson)) ~
      ("other_account" -> t.otherBankAccount.map(otherAccountToJson)) ~
      ("details" ->
        ("type" -> t.transactionType.getOrElse("")) ~
        ("label" -> t.label.getOrElse("")) ~
        ("posted" -> t.dateOption2JString(t.startDate)) ~
        ("completed" -> t.dateOption2JString(t.finishDate)) ~
        ("new_balance" ->
          ("currency" -> t.currency.getOrElse("")) ~
          ("amount" -> t.balance)) ~
        ("value" ->
          ("currency" -> t.currency.getOrElse("")) ~
          ("amount" -> t.amount))))
  }

  private def thisAccountJson(thisAccount : ModeratedBankAccount) : JObject = {
    ("holder" -> thisAccount.owners.flatten.map(ownerJson)) ~
    ("number" -> thisAccount.number.getOrElse("")) ~
    ("kind" -> thisAccount.accountType.getOrElse("")) ~
    ("bank" ->
      ("IBAN" -> thisAccount.iban.getOrElse("")) ~
      ("national_identifier" -> thisAccount.nationalIdentifier.getOrElse("")) ~
      ("name" -> thisAccount.bankName.getOrElse(""))
    )
  }

  private def ownerJson(owner : AccountOwner) : JObject = {
    ("name" -> owner.name) ~
    ("is_alias" -> false)
  }

  private def otherAccountToJson(otherAccount : ModeratedOtherBankAccount) : JObject = {
    ("holder" ->
      ("name" -> otherAccount.label.display) ~
      ("is_alias" -> otherAccount.isAlias)
    ) ~
    ("number" -> otherAccount.number.getOrElse("")) ~
    ("kind" -> otherAccount.kind.getOrElse("")) ~
    ("bank" ->
      ("IBAN" -> otherAccount.iban.getOrElse("")) ~
      ("national_identifier" -> otherAccount.nationalIdentifier.getOrElse("")) ~
      ("name" -> otherAccount.bankName.getOrElse(""))
    )
  }

  private def userToJson(user : Box[User]) : JValue =
    user match {
      case Full(u) =>
              ("id" -> u.id_) ~
              ("provider" -> u.provider ) ~
              ("display_name" -> {u.theFirstName + " " + u.theLastName})

      case _ => ("id" -> "") ~
                ("provider" -> "") ~
                ("display_name" -> "")
    }

  private def oneFieldJson(key : String, value : String) : JObject =
    (key -> value)

  private def geoTagToJson(name : String, geoTag : GeoTag) : JValue = {
    (name ->
      ("latitude" -> geoTag.latitude) ~
      ("longitude" -> geoTag.longitude) ~
      ("date" -> geoTag.datePosted.toString) ~
      ("user" -> userToJson(geoTag.postedBy))
    )
  }

  private def geoTagToJson(name : String, geoTag : Option[GeoTag]) : JValue = {
    geoTag match {
      case Some(tag) =>
      (name ->
        ("latitude" -> tag.latitude) ~
        ("longitude" -> tag.longitude) ~
        ("date" -> tag.datePosted.toString) ~
        ("user" -> userToJson(tag.postedBy))
      )
      case _ => ""
    }
  }

  private def moderatedTransactionMetadata(bankId : String, accountId : String, viewId : String, transactionID : String, user : Box[User]) : Box[ModeratedTransactionMetadata] =
    for {
      account <- BankAccount(bankId, accountId) ?~ { "bank " + bankId + " and account "  + accountId + " not found for bank"}
      view <- View.fromUrl(viewId) ?~ { "view "  + viewId + " not found"}
      moderatedTransaction <- account.moderatedTransaction(transactionID, view, user) ?~ "view/transaction not authorized"
      metadata <- Box(moderatedTransaction.metadata) ?~ {"view " + viewId + " does not authorize metadata access"}
    } yield metadata

  private def moderatedTransactionOtherAccount(bankId : String, accountId : String, viewId : String, transactionID : String, user : Box[User]) : Box[ModeratedOtherBankAccount] =
    for {
      account <- BankAccount(bankId, accountId) ?~ { "bank " + bankId + " and account "  + accountId + " not found for bank"}
      view <- View.fromUrl(viewId) ?~ { "view "  + viewId + " not found"}
      moderatedTransaction <- account.moderatedTransaction(transactionID, view, user) ?~ "view/transaction not authorized"
      otherAccount <- Box(moderatedTransaction.otherBankAccount) ?~ {"view " + viewId + " does not authorize other account access"}
    } yield otherAccount

  private def moderatedOtherAccount(bankId : String, accountId : String, viewId : String, other_account_ID : String, user : Box[User]) : Box[ModeratedOtherBankAccount] =
    for {
      account <- BankAccount(bankId, accountId) ?~ { "bank " + bankId + " and account "  + accountId + " not found for bank"}
      view <- View.fromUrl(viewId) ?~ { "view "  + viewId + " not found"}
      moderatedOtherBankAccount <- account.moderatedOtherBankAccount(other_account_ID, view, user)
    } yield moderatedOtherBankAccount

  private def moderatedOtherAccountMetadata(bankId : String, accountId : String, viewId : String, other_account_ID : String, user : Box[User]) : Box[ModeratedOtherBankAccountMetadata] =
    for {
      moderatedOtherBankAccount <- moderatedOtherAccount(bankId, accountId, viewId, other_account_ID, user)
      metadata <- Box(moderatedOtherBankAccount.metadata) ?~! {"view " + viewId + "does not allow other bank account metadata access"}
    } yield metadata

  serve("obp" / "v1.1" prefix {

    case Nil JsonGet json => {
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
          ("version" -> "1.1") ~
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

    case "banks" :: Nil JsonGet json => {
      logAPICall
      def bankToJson( b : Bank) = {
        ("bank" ->
          ("id" -> b.permalink) ~
          ("short_name" -> b.shortName) ~
          ("full_name" -> b.fullName) ~
          ("logo" -> b.logoURL) ~
          ("website" -> b.website)
        )
      }

      JsonResponse("banks" -> Bank.all.map(bankToJson _ ))
    }

  })
  serve("obp" / "v1.1" prefix {
    case "banks" :: bankId :: Nil JsonGet json => {
      logAPICall

      def bankToJson( b : Bank) = {
        ("bank" ->
          ("id" -> b.permalink) ~
          ("short_name" -> b.shortName) ~
          ("full_name" -> b.fullName) ~
          ("logo" -> b.logoURL) ~
          ("website" -> b.website)
        )
      }

      for {
        b <- Bank(bankId)
      } yield JsonResponse(bankToJson(b))
    }
  })

  serve("obp" / "v1.1" prefix {
    case "banks" :: bankId :: "accounts" :: Nil JsonGet json => {
      //log the API call
      logAPICall

      val (httpCode, message, oAuthParameters) = validator("protectedResource", httpMethod)
      val headers = ("Content-type" -> "application/x-www-form-urlencoded") :: Nil
      val user = getUser(httpCode,oAuthParameters.get("oauth_token"))

      def viewToJson(v : View) : JObject = {
        ("view" -> (
            ("id" -> v.permalink) ~
            ("short_name" -> v.name) ~
            ("description" -> v.description) ~
            ("is_public" -> v.isPublic)
        ))
      }

      def accountToJson(acc : BankAccount, user : Box[User]) : JObject = {
        //just a log
        user match {
          case Full(u) => logger.info("user " + u.emailAddress + " was found")
          case _ => logger.info("no user was found")
        }

        val views = acc permittedViews user
        ("account" -> (
          ("id" -> acc.permalink) ~
          ("views_available" -> views.map(viewToJson(_)))
        ))
      }
      def bankAccountSet2JsonResponse(bankAccounts: Set[BankAccount]): LiftResponse = {
        val accJson = bankAccounts.map(accountToJson(_,user))
        JsonResponse(("accounts" -> accJson))
      }

      Bank(bankId) match {
        case Full(bank) =>
        {
          if(isThereAnOAuthHeader)
          {
            if(httpCode == 200)
            {
              val availableAccounts = bank.accounts.filter(_.permittedViews(user).size!=0)
              bankAccountSet2JsonResponse(availableAccounts)
            }
            else
              JsonResponse(ErrorMessage(message), Nil, Nil, httpCode)
          }
          else
          {
            val availableAccounts = bank.accounts.filter(_.permittedViews(user).size!=0)
            bankAccountSet2JsonResponse(availableAccounts)
          }
        }
        case _ =>  {
          val error = "bank " + bankId + " not found"
          JsonResponse(ErrorMessage(error), Nil, Nil, httpCode)
        }
      }
    }
  })

  serve("obp" / "v1.1" prefix {
    case "banks" :: bankId :: "accounts" :: accountId :: viewId :: "account" :: Nil JsonGet json => {
      logAPICall
      val (httpCode, message, oAuthParameters) = validator("protectedResource", httpMethod)
      val headers = ("Content-type" -> "application/x-www-form-urlencoded") :: Nil
      val user = getUser(httpCode, oAuthParameters.get("oauth_token"))

      case class ModeratedAccountAndViews(account: ModeratedBankAccount, views: Set[View])

      val moderatedAccountAndViews = for {
        bank <- Bank(bankId) ?~ { "bank " + bankId + " not found" } ~> 404
        account <- BankAccount(bankId, accountId) ?~ { "account " + accountId + " not found for bank" } ~> 404
        view <- View.fromUrl(viewId) ?~ { "view " + viewId + " not found for account" } ~> 404
        moderatedAccount <- account.moderatedBankAccount(view, user) ?~ { "view/account not authorized" } ~> 401
        availableViews <- Full(account.permittedViews(user))
      } yield ModeratedAccountAndViews(moderatedAccount, availableViews)

      val bankName = moderatedAccountAndViews.flatMap(_.account.bankName) getOrElse ""

      def viewJson(view: View): JObject = {

        val isPublic: Boolean =
          view match {
            case Public => true
            case _ => false
          }

        ("id" -> view.id) ~
        ("short_name" -> view.name) ~
        ("description" -> view.description) ~
        ("is_public" -> isPublic)
      }

      def ownerJson(accountOwner: AccountOwner): JObject = {
        ("user_id" -> accountOwner.id) ~
        ("user_provider" -> bankName) ~
        ("display_name" -> accountOwner.name)
      }

      def balanceJson(account: ModeratedBankAccount): JObject = {
        ("currency" -> account.currency.getOrElse("")) ~
        ("amount" -> account.balance)
      }

      def json(account: ModeratedBankAccount, views: Set[View]): JObject = {
        ("account" ->
          ("number" -> account.number.getOrElse("")) ~
          ("owners" -> account.owners.flatten.map(ownerJson)) ~
          ("type" -> account.accountType.getOrElse("")) ~
          ("balance" -> balanceJson(account)) ~
          ("IBAN" -> account.iban.getOrElse("")) ~
          ("views_available" -> views.map(viewJson))
        )
      }

      moderatedAccountAndViews.map(mv => JsonResponse(json(mv.account, mv.views)))
    }
  })
  serve("obp" / "v1.1" prefix {
    case "banks" :: bankId :: "accounts" :: accountId :: viewId :: "transactions" :: Nil JsonGet json => {
      //log the API call
      logAPICall

      val (httpCode, message, oAuthParameters) = validator("protectedResource", httpMethod)
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

      def getTransactions(bankAccount: BankAccount, view: View, user: Option[User]) = {
        if(bankAccount.authorizedAccess(view, user)) {
          val basicParams = List(OBPLimit(limit),
                          OBPOffset(offset),
                          OBPOrdering(sortBy, sortDirection))

          val params : List[OBPQueryParam] = fromDate.toList ::: toDate.toList ::: basicParams
          bankAccount.getModeratedTransactions(params: _*)(view.moderate)
        } else Nil
      }

      def transactionsJson(transactions : List[ModeratedTransaction], v : View) : JObject = {
        ("transactions" -> transactions.map(transactionJson))
      }

      val response : Box[JsonResponse] = for {
        bankAccount <- BankAccount(bankId, accountId)
        view <- View.fromUrl(viewId) //TODO: This will have to change if we implement custom view names for different accounts
      } yield {
        val ts = getTransactions(bankAccount, view, getUser(httpCode,oAuthParameters.get("oauth_token")))
        JsonResponse(transactionsJson(ts, view),Nil, Nil, 200)
      }

      response getOrElse (JsonResponse(ErrorMessage(message), Nil, Nil, 401)) : LiftResponse
    }
  })
  serve("obp" / "v1.1" prefix {
    case "banks" :: bankId :: "accounts" :: accountId :: viewId :: "transactions" :: transactionID :: "transaction" :: Nil JsonGet json => {
      //log the API call
      logAPICall

      def transactionInJson(bankId : String, accountId : String, viewId : String, transactionID : String, user : Box[User]) : JsonResponse = {
        val moderatedTransaction = for {
            account <- BankAccount(bankId, accountId) ?~ { "bank " + bankId + " and account "  + accountId + " not found for bank"}
            view <- View.fromUrl(viewId) ?~ { "view "  + viewId + " not found"}
            moderatedTransaction <- account.moderatedTransaction(transactionID, view, user) ?~ "view/transaction not authorized"
          } yield moderatedTransaction

          moderatedTransaction match {
            case Full(transaction) => JsonResponse(transactionJson(transaction), Nil, Nil, 200)
            case Failure(msg,_,_) => JsonResponse(Extraction.decompose(ErrorMessage(msg)), Nil, Nil, 400)
            case _ => JsonResponse(Extraction.decompose(ErrorMessage("error")), Nil, Nil, 400)
          }
      }

      if(isThereAnOAuthHeader)
      {
        val (httpCode, message, oAuthParameters) = validator("protectedResource", httpMethod)
        if(httpCode == 200)
        {
          val user = getUser(httpCode,oAuthParameters.get("oauth_token"))
          transactionInJson(bankId, accountId, viewId, transactionID, user)
        }
        else
          JsonResponse(ErrorMessage(message), Nil, Nil, 400)
      }
      else
        transactionInJson(bankId, accountId, viewId, transactionID, None)
    }
  })
  serve("obp" / "v1.1" prefix {
    case "banks" :: bankId :: "accounts" :: accountId :: viewId :: "transactions" :: transactionID :: "metadata" :: "narrative" :: Nil JsonGet json => {
      //log the API call
      logAPICall

      def narrativeInJson(bankId : String, accountId : String, viewId : String, transactionID : String, user : Box[User]) : JsonResponse = {
        val narrative = for {
            metadata <- moderatedTransactionMetadata(bankId,accountId,viewId,transactionID,user)
            narrative <- Box(metadata.ownerComment) ?~ {"view " + viewId + " does not authorize narrative access"}
          } yield narrative

          narrative match {
            case Full(narrative) => JsonResponse(oneFieldJson("narrative", narrative), Nil, Nil, 200)
            case Failure(msg,_,_) => JsonResponse(Extraction.decompose(ErrorMessage(msg)), Nil, Nil, 400)
            case _ => JsonResponse(Extraction.decompose(ErrorMessage("error")), Nil, Nil, 400)
          }
      }

      if(isThereAnOAuthHeader)
      {
        val (httpCode, message, oAuthParameters) = validator("protectedResource", httpMethod)
        if(httpCode == 200)
        {
          val user = getUser(httpCode,oAuthParameters.get("oauth_token"))
          narrativeInJson(bankId, accountId, viewId, transactionID, user)
        }
        else
          JsonResponse(ErrorMessage(message), Nil, Nil, 400)
      }
      else
        narrativeInJson(bankId, accountId, viewId, transactionID, None)
    }
  })
  serve("obp" / "v1.1" prefix {
    case "banks" :: bankId :: "accounts" :: accountId :: viewId :: "transactions" :: transactionID :: "metadata" :: "narrative" :: Nil JsonPost json -> _ => {
      //log the API call
      logAPICall

      if(isThereAnOAuthHeader)
      {
        val (httpCode, message, oAuthParameters) = validator("protectedResource", httpMethod)
        if(httpCode == 200)
          tryo{
            json.extract[NarrativeJSON]
          } match {
            case Full(narrativeJson) => {

              val user = getUser(httpCode,oAuthParameters.get("oauth_token"))

              val addNarrativeFunc = for {
                  metadata <- moderatedTransactionMetadata(bankId,accountId,viewId,transactionID,user)
                  narrative <- Box(metadata.ownerComment) ?~ {"view " + viewId + " does not authorize narrative access"}
                  narrativeSetted <- isFieldAlreadySet(narrative)
                  addNarrativeFunc <- Box(metadata.saveOwnerComment) ?~ {"view " + viewId + " does not authorize narrative edit"}
                } yield addNarrativeFunc

              addNarrativeFunc match {
                case Full(addNarrative) => {
                  addNarrative(narrativeJson.narrative)
                  JsonResponse(SuccessMessage("narrative successfully saved"), Nil, Nil, 201)
                }
                case Failure(msg,_,_) => JsonResponse(ErrorMessage(msg), Nil, Nil, 400)
                case _ => JsonResponse(ErrorMessage("error"), Nil, Nil, 400)
              }
            }
            case _ => JsonResponse(ErrorMessage("wrong JSON format"), Nil, Nil, 400)
          }
        else
          JsonResponse(ErrorMessage(message), Nil, Nil, httpCode)
      }
      else
        JsonResponse(ErrorMessage("Authentication via OAuth is required"), Nil, Nil, 400)
    }
  })
  serve("obp" / "v1.1" prefix{
    case "banks" :: bankId :: "accounts" :: accountId :: viewId :: "transactions" :: transactionID :: "metadata" :: "narrative" :: Nil JsonPut json -> _ => {
      //log the API call
      logAPICall

      if(isThereAnOAuthHeader)
      {
        val (httpCode, message, oAuthParameters) = validator("protectedResource", httpMethod)
        if(httpCode == 200)
          tryo{
            json.extract[NarrativeJSON]
          } match {
            case Full(narrativeJson) => {

              val user = getUser(httpCode,oAuthParameters.get("oauth_token"))

              val addNarrativeFunc = for {
                  metadata <- moderatedTransactionMetadata(bankId,accountId,viewId,transactionID,user)
                  narrative <- Box(metadata.ownerComment) ?~ {"view " + viewId + " does not authorize narrative access"}
                  addNarrativeFunc <- Box(metadata.saveOwnerComment) ?~ {"view " + viewId + " does not authorize narrative edit"}
                } yield addNarrativeFunc

              addNarrativeFunc match {
                case Full(addNarrative) => {
                  addNarrative(narrativeJson.narrative)
                  JsonResponse(SuccessMessage("narrative successfully saved"), Nil, Nil, 201)
                }
                case Failure(msg,_,_) => JsonResponse(ErrorMessage(msg), Nil, Nil, 400)
                case _ => JsonResponse(ErrorMessage("error"), Nil, Nil, 400)
              }
            }
            case _ => JsonResponse(ErrorMessage("wrong JSON format"), Nil, Nil, 400)
          }
        else
          JsonResponse(ErrorMessage(message), Nil, Nil, 400)
      }
      else
        JsonResponse(ErrorMessage("Authentication via OAuth is required"), Nil, Nil, 400)
    }
  })
  serve("obp" / "v1.1" prefix{
    case "banks" :: bankId :: "accounts" :: accountId :: viewId :: "transactions" :: transactionID :: "metadata" :: "comments" :: Nil JsonGet json => {
      //log the API call
      logAPICall

      def commentToJson(comment : code.model.traits.Comment) : JValue = {
        ("comment" ->
          ("id" -> comment.id_) ~
          ("date" -> comment.datePosted.toString) ~
          ("value" -> comment.text) ~
          ("user" -> userToJson(comment.postedBy)) ~
          ("reply_to" -> comment.replyToID)
        )
      }

      def commentsToJson(comments : List[code.model.traits.Comment]) : JValue = {
        ("comments" -> comments.map(commentToJson))
      }

      def commentsResponce(bankId : String, accountId : String, viewId : String, transactionID : String, user : Box[User]) : JsonResponse = {
        val comments = for {
            metadata <- moderatedTransactionMetadata(bankId,accountId,viewId,transactionID,user)
            comments <- Box(metadata.comments) ?~ {"view " + viewId + " does not authorize comments access"}
          } yield comments

          comments match {
            case Full(commentsList) => JsonResponse(commentsToJson(commentsList), Nil, Nil, 200)
            case Failure(msg,_,_) => JsonResponse(Extraction.decompose(ErrorMessage(msg)), Nil, Nil, 400)
            case _ => JsonResponse(Extraction.decompose(ErrorMessage("error")), Nil, Nil, 400)
          }
      }

      if(isThereAnOAuthHeader)
      {
        val (httpCode, message, oAuthParameters) = validator("protectedResource", httpMethod)
        if(httpCode == 200)
        {
          val user = getUser(httpCode,oAuthParameters.get("oauth_token"))
          commentsResponce(bankId, accountId, viewId, transactionID, user)
        }
        else
          JsonResponse(ErrorMessage(message), Nil, Nil, 400)
      }
      else
        commentsResponce(bankId, accountId, viewId, transactionID, None)
    }
  })
  serve("obp" / "v1.1" prefix{
    case "banks" :: bankId :: "accounts" :: accountId :: viewId :: "transactions" :: transactionID :: "metadata" :: "comments" :: Nil JsonPost json -> _ => {
      //log the API call
      logAPICall

      if(isThereAnOAuthHeader)
      {
        val (httpCode, message, oAuthParameters) = validator("protectedResource", httpMethod)
        if(httpCode == 200)
          tryo{
            json.extract[CommentJSON]
          } match {
            case Full(commentJson) => {
              def addComment(user : User, viewID : Long, text: String, datePosted : Date) = {
                val addComment = for {
                  metadata <- moderatedTransactionMetadata(bankId,accountId,viewId,transactionID,Full(user))
                  addCommentFunc <- Box(metadata.addComment) ?~ {"view " + viewId + " does not authorize adding comment"}
                } yield addCommentFunc

                addComment.map(
                  func =>{
                    func(user.id_, viewID, text, datePosted)
                    Full(text)
                  }
                )
              }

              val comment = for{
                  user <- getUser(httpCode,oAuthParameters.get("oauth_token")) ?~ "User not found. Authentication via OAuth is required"
                  view <- View.fromUrl(viewId) ?~ {"view " + viewId +" view not found"}
                  postedComment <- addComment(user, view.id, commentJson.value, commentJson.posted_date)
                } yield postedComment

              comment match {
                case Full(text) => JsonResponse(SuccessMessage("comment : " + text + "successfully saved"), Nil, Nil, 201)
                case Failure(msg, _, _) => JsonResponse(ErrorMessage(msg), Nil, Nil, 400)
                case _ => JsonResponse(ErrorMessage("error"), Nil, Nil, 400)
              }
            }
            case _ => JsonResponse(ErrorMessage("wrong JSON format"), Nil, Nil, 400)
          }
        else
          JsonResponse(ErrorMessage(message), Nil, Nil, httpCode)
      }
      else
        JsonResponse(ErrorMessage("Authentication via OAuth is required"), Nil, Nil, 400)
    }
  })
  serve("obp" / "v1.1" prefix {
    case "banks" :: bankId :: "accounts" :: accountId :: viewId :: "transactions" :: transactionID :: "metadata" :: "tags" :: Nil JsonGet json => {
      //log the API call
      logAPICall

      def tagToJson(tag : Tag) : JValue = {
        ("tag" ->
          ("id" -> tag.id_) ~
          ("date" -> tag.datePosted.toString) ~
          ("value" -> tag.value) ~
          ("user" -> userToJson(tag.postedBy))
        )
      }

      def tagsToJson(tags : List[Tag]) : JValue = {
        ("tags" -> tags.map(tagToJson))
      }

      def tagsResponce(bankId : String, accountId : String, viewId : String, transactionID : String, user : Box[User]) : JsonResponse = {
        val tags = for {
            metadata <- moderatedTransactionMetadata(bankId,accountId,viewId,transactionID,user)
            tags <- Box(metadata.tags) ?~ {"view " + viewId + " does not authorize tags access"}
          } yield tags

          tags match {
            case Full(tagsList) => JsonResponse(tagsToJson(tagsList), Nil, Nil, 200)
            case Failure(msg,_,_) => JsonResponse(Extraction.decompose(ErrorMessage(msg)), Nil, Nil, 400)
            case _ => JsonResponse(Extraction.decompose(ErrorMessage("error")), Nil, Nil, 400)
          }
      }

      if(isThereAnOAuthHeader)
      {
        val (httpCode, message, oAuthParameters) = validator("protectedResource", httpMethod)
        if(httpCode == 200)
        {
          val user = getUser(httpCode,oAuthParameters.get("oauth_token"))
          tagsResponce(bankId, accountId, viewId, transactionID, user)
        }
        else
          JsonResponse(ErrorMessage(message), Nil, Nil, 400)
      }
      else
        tagsResponce(bankId, accountId, viewId, transactionID, None)
    }
  })
  serve("obp" / "v1.1" prefix {
    //post a tag
    case "banks" :: bankId :: "accounts" :: accountId :: viewId :: "transactions" :: transactionID :: "metadata" :: "tags" :: Nil JsonPost json -> _ => {
      //log the API call
      logAPICall

      if(isThereAnOAuthHeader)
      {
        val (httpCode, message, oAuthParameters) = validator("protectedResource", httpMethod)
        if(httpCode == 200)
          tryo{
            json.extract[TagJSON]
          } match {
            case Full(tagJson) => {
              if(! tagJson.value.contains(" "))
              {
                def addTag(user : User, viewID : Long, tag: String, datePosted : Date) = {
                  val addTag = for {
                    metadata <- moderatedTransactionMetadata(bankId,accountId,viewId,transactionID,Full(user))
                    addTagFunc <- Box(metadata.addTag) ?~ {"view " + viewId + " does not authorize adding comment"}
                  } yield addTagFunc

                  addTag.map(
                    func =>{
                      Full(func(user.id_, viewID, tag, datePosted))
                    }
                  )
                }

                val tag = for{
                    user <- getUser(httpCode,oAuthParameters.get("oauth_token")) ?~ "User not found. Authentication via OAuth is required"
                    view <- View.fromUrl(viewId) ?~ {"view " + viewId +" view not found"}
                    postedTagID <- addTag(user, view.id, tagJson.value, tagJson.posted_date)
                  } yield postedTagID

                tag match {
                  case Full(postedTagID) => JsonResponse(SuccessMessage("tag : " + postedTagID + "successfully saved"), Nil, Nil, 201)
                  case Failure(msg, _, _) => JsonResponse(ErrorMessage(msg), Nil, Nil, 400)
                  case _ => JsonResponse(ErrorMessage("error"), Nil, Nil, 400)
                }
              }
              else
              {
                JsonResponse(ErrorMessage("tag value MUST NOT contain a white space"), Nil, Nil, 400)
              }
            }
            case _ => JsonResponse(ErrorMessage("wrong JSON format"), Nil, Nil, 400)
          }
        else
          JsonResponse(ErrorMessage(message), Nil, Nil, httpCode)
      }
      else
        JsonResponse(ErrorMessage("Authentication via OAuth is required"), Nil, Nil, 400)
    }
  })
  serve("obp" / "v1.1" prefix {
    case "banks" :: bankId :: "accounts" :: accountId :: viewId :: "transactions" :: transactionID :: "metadata" :: "images" :: Nil JsonGet json => {
      //log the API call
      logAPICall

      def imageToJson(image : TransactionImage) : JValue = {
        ("image" ->
          ("id" -> image.id_) ~
          ("label" -> image.description) ~
          ("URL" -> image.imageUrl.toString) ~
          ("date" -> image.datePosted.toString) ~
          ("user" -> userToJson(image.postedBy))
        )
      }

      def imagesToJson(images : List[TransactionImage]) : JValue = {
        ("images" -> images.map(imageToJson))
      }

      def imagesResponce(bankId : String, accountId : String, viewId : String, transactionID : String, user : Box[User]) : JsonResponse = {
        val images = for {
            metadata <- moderatedTransactionMetadata(bankId,accountId,viewId,transactionID,user)
            images <- Box(metadata.images) ?~ {"view " + viewId + " does not authorize tags access"}
          } yield images

          images match {
            case Full(imagesList) => JsonResponse(imagesToJson(imagesList), Nil, Nil, 200)
            case Failure(msg,_,_) => JsonResponse(Extraction.decompose(ErrorMessage(msg)), Nil, Nil, 400)
            case _ => JsonResponse(Extraction.decompose(ErrorMessage("error")), Nil, Nil, 400)
          }
      }

      if(isThereAnOAuthHeader)
      {
        val (httpCode, message, oAuthParameters) = validator("protectedResource", httpMethod)
        if(httpCode == 200)
        {
          val user = getUser(httpCode,oAuthParameters.get("oauth_token"))
          imagesResponce(bankId, accountId, viewId, transactionID, user)
        }
        else
          JsonResponse(ErrorMessage(message), Nil, Nil, 400)
      }
      else
        imagesResponce(bankId, accountId, viewId, transactionID, None)
    }
  })
  serve("obp" / "v1.1" prefix {
    //post an image
    case "banks" :: bankId :: "accounts" :: accountId :: viewId :: "transactions" :: transactionID :: "metadata" :: "images" :: Nil JsonPost json -> _ => {
      //log the API call
      logAPICall

      if(isThereAnOAuthHeader)
      {
        val (httpCode, message, oAuthParameters) = validator("protectedResource", httpMethod)
        if(httpCode == 200)
          tryo{
            json.extract[ImageJSON]
          } match {
            case Full(imageJson) => {
              def addImage(user : User, viewID : Long, label: String, url : URL) : Box[String] = {
                val addImage = for {
                  metadata <- moderatedTransactionMetadata(bankId,accountId,viewId,transactionID,Full(user))
                  addImageFunc <- Box(metadata.addImage) ?~ {"view " + viewId + " does not authorize adding comment"}
                } yield addImageFunc

                addImage.map(
                  func =>{
                    val datePosted = (now: TimeSpan)
                    func(user.id_, viewID, label, datePosted, url)
                  }
                )
              }

              val imageId = for{
                  user <- getUser(httpCode,oAuthParameters.get("oauth_token")) ?~ "User not found. Authentication via OAuth is required"
                  view <- View.fromUrl(viewId) ?~ {"view " + viewId +" view not found"}
                  url <- tryo{new URL(imageJson.URL)} ?~! "Could not parse url string as a valid URL"
                  postedImageId <- addImage(user, view.id, imageJson.label, url)
                } yield postedImageId

              imageId match {
                case Full(postedImageId) => JsonResponse(SuccessMessage("image : " + postedImageId + "successfully saved"), Nil, Nil, 201)
                case Failure(msg, _, _) => JsonResponse(ErrorMessage(msg), Nil, Nil, 400)
                case _ => JsonResponse(ErrorMessage("error"), Nil, Nil, 400)
              }
            }
            case _ => JsonResponse(ErrorMessage("wrong JSON format"), Nil, Nil, 400)
          }
        else
          JsonResponse(ErrorMessage(message), Nil, Nil, httpCode)
      }
      else
        JsonResponse(ErrorMessage("Authentication via OAuth is required"), Nil, Nil, 400)
    }
  })
  serve("obp" / "v1.1" prefix {
    case "banks" :: bankId :: "accounts" :: accountId :: viewId :: "transactions" :: transactionID :: "metadata" :: "where" :: Nil JsonGet json => {
      //log the API call
      logAPICall

      def whereTagResponce(bankId : String, accountId : String, viewId : String, transactionID : String, user : Box[User]) : JsonResponse = {
        val whereTag = for {
            metadata <- moderatedTransactionMetadata(bankId,accountId,viewId,transactionID,user)
            whereTag <- Box(metadata.whereTag) ?~ {"view " + viewId + " does not authorize tags access"}
          } yield whereTag

          whereTag match {
            case Full(whereTag) => JsonResponse(geoTagToJson("where", whereTag), Nil, Nil, 200)
            case Failure(msg,_,_) => JsonResponse(Extraction.decompose(ErrorMessage(msg)), Nil, Nil, 400)
            case _ => JsonResponse(Extraction.decompose(ErrorMessage("error")), Nil, Nil, 400)
          }
      }

      if(isThereAnOAuthHeader)
      {
        val (httpCode, message, oAuthParameters) = validator("protectedResource", httpMethod)
        if(httpCode == 200)
        {
          val user = getUser(httpCode, oAuthParameters.get("oauth_token"))
          whereTagResponce(bankId, accountId, viewId, transactionID, user)
        }
        else
          JsonResponse(ErrorMessage(message), Nil, Nil, 400)
      }
      else
        whereTagResponce(bankId, accountId, viewId, transactionID, None)
    }
  })
  serve("obp" / "v1.1" prefix{
    case "banks" :: bankID :: "accounts" :: accountID :: viewId :: "transactions" :: transactionID :: "metadata" :: "where" :: Nil JsonPost json -> _ => {
      //log the API call
      logAPICall

      if(isThereAnOAuthHeader)
      {
        val (httpCode, message, oAuthParameters) = validator("protectedResource", httpMethod)
        if(httpCode == 200)
          tryo{
            json.extract[WhereTagJSON]
          } match {
            case Full(whereTagJson) => {
              def addWhereTag(user : User, viewID : Long, longitude: Double, latitude : Double) : Box[Boolean] = {
                val addWhereTag = for {
                  metadata <- moderatedTransactionMetadata(bankID,accountID,viewId,transactionID,Full(user))
                  addWhereTagFunc <- Box(metadata.addWhereTag) ?~ {"view " + viewId + " does not authorize adding where tag"}
                } yield addWhereTagFunc

                addWhereTag.map(
                  func =>{
                    val datePosted = (now: TimeSpan)
                    func(user.id_, viewID, datePosted, longitude, latitude)
                  }
                )
              }

              val postedGeoTag = for{
                  user <- getUser(httpCode,oAuthParameters.get("oauth_token")) ?~ "User not found. Authentication via OAuth is required"
                  view <- View.fromUrl(viewId) ?~ {"view " + viewId +" view not found"}
                  posterWheteTag <- addWhereTag(user, view.id, whereTagJson.where.longitude, whereTagJson.where.latitude)
                } yield posterWheteTag

              postedGeoTag match {
                case Full(postedWhereTag) =>
                  if(postedWhereTag)
                    JsonResponse(SuccessMessage("Geo tag successfully saved"), Nil, Nil, 201)
                  else
                    JsonResponse(ErrorMessage("Geo tag could not be saved"), Nil, Nil, 500)
                case Failure(msg, _, _) => JsonResponse(ErrorMessage(msg), Nil, Nil, 400)
                case _ => JsonResponse(ErrorMessage("error"), Nil, Nil, 400)
              }
            }
            case _ => JsonResponse(ErrorMessage("wrong JSON format"), Nil, Nil, 400)
          }
        else
          JsonResponse(ErrorMessage(message), Nil, Nil, httpCode)
      }
      else
        JsonResponse(ErrorMessage("Authentication via OAuth is required"), Nil, Nil, 400)
    }
  })
  serve("obp" / "v1.1" prefix{
    case "banks" :: bankID :: "accounts" :: accountID :: viewId :: "transactions" :: transactionID :: "metadata" :: "where" :: Nil JsonPut json -> _ => {
      //log the API call
      logAPICall

      if(isThereAnOAuthHeader)
      {
        val (httpCode, message, oAuthParameters) = validator("protectedResource", httpMethod)
        if(httpCode == 200)
          tryo{
            json.extract[WhereTagJSON]
          } match {
            case Full(whereTagJson) => {
              def addWhereTag(user : User, viewID : Long, longitude: Double, latitude : Double) : Box[Boolean] = {
                val addWhereTag = for {
                  metadata <- moderatedTransactionMetadata(bankID,accountID,viewId,transactionID,Full(user))
                  addWhereTagFunc <- Box(metadata.addWhereTag) ?~ {"view " + viewId + " does not authorize adding where tag"}
                } yield addWhereTagFunc

                addWhereTag.map(
                  func =>{
                    val datePosted = (now: TimeSpan)
                    func(user.id_, viewID, datePosted, longitude, latitude)
                  }
                )
              }

              val postedGeoTag = for{
                  user <- getUser(httpCode,oAuthParameters.get("oauth_token")) ?~ "User not found. Authentication via OAuth is required"
                  view <- View.fromUrl(viewId) ?~ {"view " + viewId +" view not found"}
                  posterWheteTag <- addWhereTag(user, view.id, whereTagJson.where.longitude, whereTagJson.where.latitude)
                } yield posterWheteTag

              postedGeoTag match {
                case Full(postedWhereTag) =>
                  if(postedWhereTag)
                    JsonResponse(SuccessMessage("Geo tag successfully saved"), Nil, Nil, 201)
                  else
                    JsonResponse(ErrorMessage("Geo tag could not be saved"), Nil, Nil, 500)
                case Failure(msg, _, _) => JsonResponse(ErrorMessage(msg), Nil, Nil, 400)
                case _ => JsonResponse(ErrorMessage("error"), Nil, Nil, 400)
              }
            }
            case _ => JsonResponse(ErrorMessage("wrong JSON format"), Nil, Nil, 400)
          }
        else
          JsonResponse(ErrorMessage(message), Nil, Nil, httpCode)
      }
      else
        JsonResponse(ErrorMessage("Authentication via OAuth is required"), Nil, Nil, 400)
    }
  })
  serve("obp" / "v1.1" prefix {
    case "banks" :: bankId :: "accounts" :: accountId :: viewId :: "transactions" :: transactionID :: "other_account" :: Nil JsonGet json => {
      //log the API call
      logAPICall

      def otherAccountToJson(otherAccount : ModeratedOtherBankAccount) : JObject = {
        ("id" -> otherAccount.id) ~
        ("number" -> otherAccount.number.getOrElse("")) ~
        ("holder" ->
          ("name" -> otherAccount.label.display) ~
          ("is_alias" -> otherAccount.isAlias)
        ) ~
        ("national_identifier" -> otherAccount.nationalIdentifier.getOrElse("")) ~
        ("IBAN" -> otherAccount.iban.getOrElse("")) ~
        ("bank_name" -> otherAccount.bankName.getOrElse("")) ~
        ("swift_bic" -> otherAccount.swift_bic.getOrElse(""))
      }

      def otherAccountResponce(bankId : String, accountId : String, viewId : String, transactionID : String, user : Box[User]) : JsonResponse = {
        moderatedTransactionOtherAccount(bankId,accountId,viewId,transactionID,user) match {
            case Full(otherAccount) => JsonResponse(otherAccountToJson(otherAccount), Nil, Nil, 200)
            case Failure(msg,_,_) => JsonResponse(Extraction.decompose(ErrorMessage(msg)), Nil, Nil, 400)
            case _ => JsonResponse(Extraction.decompose(ErrorMessage("error")), Nil, Nil, 400)
          }
      }

      if(isThereAnOAuthHeader)
      {
        val (httpCode, message, oAuthParameters) = validator("protectedResource", httpMethod)
        if(httpCode == 200)
        {
          val user = getUser(httpCode, oAuthParameters.get("oauth_token"))
          otherAccountResponce(bankId, accountId, viewId, transactionID, user)
        }
        else
          JsonResponse(ErrorMessage(message), Nil, Nil, 400)
      }
      else
        otherAccountResponce(bankId, accountId, viewId, transactionID, None)
    }
  })
  serve("obp" / "v1.1" prefix {
    case "banks" :: bankId :: "accounts" :: accountId :: viewId :: "other_accounts" :: other_account_ID :: "metadata" :: Nil JsonGet json => {
      //log the API call
      logAPICall

      def otherAccountMetadataToJson(metadata : ModeratedOtherBankAccountMetadata) : JObject = {
        ("more_info" -> metadata.moreInfo.getOrElse("")) ~
        ("URL" -> metadata.url.getOrElse("")) ~
        ("image_URL" -> metadata.imageUrl.getOrElse("")) ~
        ("open_corporates_URL" -> metadata.openCorporatesUrl.getOrElse("")) ~
        ("corporate_location" -> geoTagToJson("corporate_location",metadata.corporateLocation)) ~
        ("physical_location" -> geoTagToJson("physical_location",metadata.physicalLocation))
      }

      def otherAccountMetadataResponce(bankId : String, accountId : String, viewId : String, other_account_ID : String, user : Box[User]) : JsonResponse = {
        val otherAccountMetaData = for{
          otherAccount <- moderatedOtherAccount(bankId, accountId, viewId, other_account_ID, user)
          metaData <- Box(otherAccount.metadata) ?~! {"view " + viewId + "does not allow other account metadata access" }
        } yield metaData

        otherAccountMetaData match {
            case Full(metadata) => JsonResponse(otherAccountMetadataToJson(metadata), Nil, Nil, 200)
            case Failure(msg,_,_) => JsonResponse(Extraction.decompose(ErrorMessage(msg)), Nil, Nil, 400)
            case _ => JsonResponse(Extraction.decompose(ErrorMessage("error")), Nil, Nil, 400)
          }
      }

      if(isThereAnOAuthHeader)
      {
        val (httpCode, message, oAuthParameters) = validator("protectedResource", httpMethod)
        if(httpCode == 200)
        {
          val user = getUser(httpCode, oAuthParameters.get("oauth_token"))
          otherAccountMetadataResponce(bankId, accountId, viewId, other_account_ID, user)
        }
        else
          JsonResponse(ErrorMessage(message), Nil, Nil, 400)
      }
      else
        otherAccountMetadataResponce(bankId, accountId, viewId, other_account_ID, None)
    }
  })
  serve("obp" / "v1.1" prefix{
    case "banks" :: bankId :: "accounts" :: accountId :: viewId :: "other_accounts" :: otherAccountId :: "metadata" :: "more_info" :: Nil JsonPost json -> _ => {
      //log the API call
      logAPICall

      def postMoreInfoResponce(bankId : String, accountId : String, viewId : String, otherAccountId: String, user : Box[User]) : JsonResponse =
        tryo{
            json.extract[MoreInfoJSON]
          } match {
            case Full(moreInfoJson) => {

              def addMoreInfo(bankId : String, accountId : String, viewId : String, otherAccountId : String, user : Box[User], moreInfo : String): Box[Boolean] = {
                val addMoreInfo = for {
                  metadata <- moderatedOtherAccountMetadata(bankId,accountId,viewId,otherAccountId,user)
                  moreInfo <- Box(metadata.moreInfo) ?~! {"view " + viewId + " does not authorize access to more_info"}
                  setMoreInfo <- isFieldAlreadySet(moreInfo)
                  addMoreInfo <- Box(metadata.addMoreInfo) ?~ {"view " + viewId + " does not authorize adding more_info"}
                } yield addMoreInfo

                addMoreInfo.map(
                  func =>{
                    func(moreInfo)
                  }
                )
              }

              addMoreInfo(bankId, accountId, viewId, otherAccountId, user, moreInfoJson.more_info) match {
                case Full(posted) =>
                  if(posted)
                    JsonResponse(Extraction.decompose(SuccessMessage("more info successfully saved")), Nil, Nil, 201)
                  else
                    JsonResponse(Extraction.decompose(ErrorMessage("more info could not be saved")), Nil, Nil, 500)
                case Failure(msg, _, _) => JsonResponse(Extraction.decompose(ErrorMessage(msg)), Nil, Nil, 400)
                case _ => JsonResponse(Extraction.decompose(ErrorMessage("error")), Nil, Nil, 400)
              }
            }
            case _ => JsonResponse(Extraction.decompose(ErrorMessage("wrong JSON format")), Nil, Nil, 400)
          }


      if(isThereAnOAuthHeader)
      {
        val (httpCode, message, oAuthParameters) = validator("protectedResource", httpMethod)
        if(httpCode == 200)
        {
          val user = getUser(httpCode, oAuthParameters.get("oauth_token"))
          postMoreInfoResponce(bankId, accountId, viewId, otherAccountId, user)
        }
        else
          JsonResponse(ErrorMessage(message), Nil, Nil, httpCode)
      }
      else
        postMoreInfoResponce(bankId, accountId, viewId, otherAccountId, Empty)
    }
  })
  serve("obp" / "v1.1" prefix{
    case "banks" :: bankId :: "accounts" :: accountId :: viewId :: "other_accounts" :: otherAccountId :: "metadata" :: "more_info" :: Nil JsonPut json -> _ => {
      //log the API call
      logAPICall

      def updateMoreInfoResponce(bankId : String, accountId : String, viewId : String, otherAccountId: String, user : Box[User]) : JsonResponse =
        tryo{
            json.extract[MoreInfoJSON]
          } match {
            case Full(moreInfoJson) => {

              def addMoreInfo(bankId : String, accountId : String, viewId : String, otherAccountId : String, user : Box[User], moreInfo : String): Box[Boolean] = {
                val addMoreInfo = for {
                  metadata <- moderatedOtherAccountMetadata(bankId,accountId,viewId,otherAccountId,user)
                  addMoreInfo <- Box(metadata.addMoreInfo) ?~ {"view " + viewId + " does not authorize adding more_info"}
                } yield addMoreInfo

                addMoreInfo.map(
                  func =>{
                    func(moreInfo)
                  }
                )
              }

              addMoreInfo(bankId, accountId, viewId, otherAccountId, user, moreInfoJson.more_info) match {
                case Full(posted) =>
                  if(posted)
                    JsonResponse(Extraction.decompose(SuccessMessage("more info successfully saved")), Nil, Nil, 201)
                  else
                    JsonResponse(Extraction.decompose(ErrorMessage("more info could not be saved")), Nil, Nil, 500)
                case Failure(msg, _, _) => JsonResponse(Extraction.decompose(ErrorMessage(msg)), Nil, Nil, 400)
                case _ => JsonResponse(Extraction.decompose(ErrorMessage("error")), Nil, Nil, 400)
              }
            }
            case _ => JsonResponse(Extraction.decompose(ErrorMessage("wrong JSON format")), Nil, Nil, 400)
          }


      if(isThereAnOAuthHeader)
      {
        val (httpCode, message, oAuthParameters) = validator("protectedResource", httpMethod)
        if(httpCode == 200)
        {
          val user = getUser(httpCode, oAuthParameters.get("oauth_token"))
          updateMoreInfoResponce(bankId, accountId, viewId, otherAccountId, user)
        }
        else
          JsonResponse(ErrorMessage(message), Nil, Nil, httpCode)
      }
      else
        updateMoreInfoResponce(bankId, accountId, viewId, otherAccountId, Empty)
    }
  })
  serve("obp" / "v1.1" prefix{
    case "banks" :: bankId :: "accounts" :: accountId :: viewId :: "other_accounts" :: otherAccountId :: "metadata" :: "url" :: Nil JsonPost json -> _ => {
      //log the API call
      logAPICall

      def postURLResponce(bankId : String, accountId : String, viewId : String, otherAccountId: String, user : Box[User]) : JsonResponse =
        tryo{
            json.extract[UrlJSON]
          } match {
            case Full(urlJson) => {

              def addUrl(bankId : String, accountId : String, viewId : String, otherAccountId : String, user : Box[User], url : String): Box[Boolean] = {
                val addUrl = for {
                  metadata <- moderatedOtherAccountMetadata(bankId,accountId,viewId,otherAccountId,user)
                  url <- Box(metadata.url) ?~! {"view " + viewId + " does not authorize access to URL"}
                  setUrl <- isFieldAlreadySet(url)
                  addUrl <- Box(metadata.addUrl) ?~ {"view " + viewId + " does not authorize adding URL"}
                } yield addUrl

                addUrl.map(
                  func =>{
                    func(url)
                  }
                )
              }

              addUrl(bankId, accountId, viewId, otherAccountId, user, urlJson.URL) match {
                case Full(posted) =>
                  if(posted)
                    JsonResponse(Extraction.decompose(SuccessMessage("URL successfully saved")), Nil, Nil, 201)
                  else
                    JsonResponse(Extraction.decompose(ErrorMessage("URL could not be saved")), Nil, Nil, 500)
                case Failure(msg, _, _) => JsonResponse(Extraction.decompose(ErrorMessage(msg)), Nil, Nil, 400)
                case _ => JsonResponse(Extraction.decompose(ErrorMessage("error")), Nil, Nil, 400)
              }
            }
            case _ => JsonResponse(Extraction.decompose(ErrorMessage("wrong JSON format")), Nil, Nil, 400)
          }


      if(isThereAnOAuthHeader)
      {
        val (httpCode, message, oAuthParameters) = validator("protectedResource", httpMethod)
        if(httpCode == 200)
        {
          val user = getUser(httpCode, oAuthParameters.get("oauth_token"))
          postURLResponce(bankId, accountId, viewId, otherAccountId, user)
        }
        else
          JsonResponse(ErrorMessage(message), Nil, Nil, httpCode)
      }
      else
        postURLResponce(bankId, accountId, viewId, otherAccountId, Empty)
    }
  })
  serve("obp" / "v1.1" prefix{
    case "banks" :: bankId :: "accounts" :: accountId :: viewId :: "other_accounts" :: otherAccountId :: "metadata" :: "url" :: Nil JsonPut json -> _ => {
      //log the API call
      logAPICall

      def updateURLResponce(bankId : String, accountId : String, viewId : String, otherAccountId: String, user : Box[User]) : JsonResponse =
        tryo{
            json.extract[UrlJSON]
          } match {
            case Full(urlJson) => {

              def addUrl(bankId : String, accountId : String, viewId : String, otherAccountId : String, user : Box[User], url : String): Box[Boolean] = {
                val addUrl = for {
                  metadata <- moderatedOtherAccountMetadata(bankId,accountId,viewId,otherAccountId,user)
                  addUrl <- Box(metadata.addUrl) ?~ {"view " + viewId + " does not authorize adding URL"}
                } yield addUrl

                addUrl.map(
                  func =>{
                    func(url)
                  }
                )
              }

              addUrl(bankId, accountId, viewId, otherAccountId, user, urlJson.URL) match {
                case Full(posted) =>
                  if(posted)
                    JsonResponse(Extraction.decompose(SuccessMessage("URL successfully saved")), Nil, Nil, 201)
                  else
                    JsonResponse(Extraction.decompose(ErrorMessage("URL could not be saved")), Nil, Nil, 500)
                case Failure(msg, _, _) => JsonResponse(Extraction.decompose(ErrorMessage(msg)), Nil, Nil, 400)
                case _ => JsonResponse(Extraction.decompose(ErrorMessage("error")), Nil, Nil, 400)
              }
            }
            case _ => JsonResponse(Extraction.decompose(ErrorMessage("wrong JSON format")), Nil, Nil, 400)
          }


      if(isThereAnOAuthHeader)
      {
        val (httpCode, message, oAuthParameters) = validator("protectedResource", httpMethod)
        if(httpCode == 200)
        {
          val user = getUser(httpCode, oAuthParameters.get("oauth_token"))
          updateURLResponce(bankId, accountId, viewId, otherAccountId, user)
        }
        else
          JsonResponse(ErrorMessage(message), Nil, Nil, httpCode)
      }
      else
        updateURLResponce(bankId, accountId, viewId, otherAccountId, Empty)
    }
  })
  serve("obp" / "v1.1" prefix{
    case "banks" :: bankId :: "accounts" :: accountId :: viewId :: "other_accounts" :: otherAccountId :: "metadata" :: "image_url" :: Nil JsonPost json -> _ => {
      //log the API call
      logAPICall

      def postImageUrlResponce(bankId : String, accountId : String, viewId : String, otherAccountId: String, user : Box[User]) : JsonResponse =
        tryo{
            json.extract[ImageUrlJSON]
          } match {
            case Full(imageUrlJson) => {

              def addImageUrl(bankId : String, accountId : String, viewId : String, otherAccountId : String, user : Box[User], url : String): Box[Boolean] = {
                val addImageUrl = for {
                  metadata <- moderatedOtherAccountMetadata(bankId,accountId,viewId,otherAccountId,user)
                  imageUrl <- Box(metadata.imageUrl) ?~! {"view " + viewId + " does not authorize access to image URL"}
                  setImageUrl <- isFieldAlreadySet(imageUrl)
                  addImageUrl <- Box(metadata.addImageUrl) ?~ {"view " + viewId + " does not authorize adding image URL"}
                } yield addImageUrl

                addImageUrl.map(
                  func =>{
                    func(url)
                  }
                )
              }

              addImageUrl(bankId, accountId, viewId, otherAccountId, user, imageUrlJson.image_URL) match {
                case Full(posted) =>
                  if(posted)
                    JsonResponse(Extraction.decompose(SuccessMessage("Image URL successfully saved")), Nil, Nil, 201)
                  else
                    JsonResponse(Extraction.decompose(ErrorMessage("Image URL could not be saved")), Nil, Nil, 500)
                case Failure(msg, _, _) => JsonResponse(Extraction.decompose(ErrorMessage(msg)), Nil, Nil, 400)
                case _ => JsonResponse(Extraction.decompose(ErrorMessage("error")), Nil, Nil, 400)
              }
            }
            case _ => JsonResponse(Extraction.decompose(ErrorMessage("wrong JSON format")), Nil, Nil, 400)
          }


      if(isThereAnOAuthHeader)
      {
        val (httpCode, message, oAuthParameters) = validator("protectedResource", httpMethod)
        if(httpCode == 200)
        {
          val user = getUser(httpCode, oAuthParameters.get("oauth_token"))
          postImageUrlResponce(bankId, accountId, viewId, otherAccountId, user)
        }
        else
          JsonResponse(ErrorMessage(message), Nil, Nil, httpCode)
      }
      else
        postImageUrlResponce(bankId, accountId, viewId, otherAccountId, Empty)
    }
  })
  serve("obp" / "v1.1" prefix{
    case "banks" :: bankId :: "accounts" :: accountId :: viewId :: "other_accounts" :: otherAccountId :: "metadata" :: "image_url" :: Nil JsonPut json -> _ => {
      //log the API call
      logAPICall

      def updateImageUrlResponce(bankId : String, accountId : String, viewId : String, otherAccountId: String, user : Box[User]) : JsonResponse =
        tryo{
            json.extract[ImageUrlJSON]
          } match {
            case Full(imageUrlJson) => {

              def addImageUrl(bankId : String, accountId : String, viewId : String, otherAccountId : String, user : Box[User], url : String): Box[Boolean] = {
                val addImageUrl = for {
                  metadata <- moderatedOtherAccountMetadata(bankId,accountId,viewId,otherAccountId,user)
                  addImageUrl <- Box(metadata.addImageUrl) ?~ {"view " + viewId + " does not authorize adding image URL"}
                } yield addImageUrl

                addImageUrl.map(
                  func =>{
                    func(url)
                  }
                )
              }

              addImageUrl(bankId, accountId, viewId, otherAccountId, user, imageUrlJson.image_URL) match {
                case Full(posted) =>
                  if(posted)
                    JsonResponse(Extraction.decompose(SuccessMessage("Image URL successfully saved")), Nil, Nil, 201)
                  else
                    JsonResponse(Extraction.decompose(ErrorMessage("Image URL could not be saved")), Nil, Nil, 500)
                case Failure(msg, _, _) => JsonResponse(Extraction.decompose(ErrorMessage(msg)), Nil, Nil, 400)
                case _ => JsonResponse(Extraction.decompose(ErrorMessage("error")), Nil, Nil, 400)
              }
            }
            case _ => JsonResponse(Extraction.decompose(ErrorMessage("wrong JSON format")), Nil, Nil, 400)
          }


      if(isThereAnOAuthHeader)
      {
        val (httpCode, message, oAuthParameters) = validator("protectedResource", httpMethod)
        if(httpCode == 200)
        {
          val user = getUser(httpCode, oAuthParameters.get("oauth_token"))
          updateImageUrlResponce(bankId, accountId, viewId, otherAccountId, user)
        }
        else
          JsonResponse(ErrorMessage(message), Nil, Nil, httpCode)
      }
      else
        updateImageUrlResponce(bankId, accountId, viewId, otherAccountId, Empty)
    }
  })
  serve("obp" / "v1.1" prefix{
    case "banks" :: bankId :: "accounts" :: accountId :: viewId :: "other_accounts" :: otherAccountId :: "metadata" :: "open_corporates_url" :: Nil JsonPost json -> _ => {
      //log the API call
      logAPICall

      def postOpenCorporatesUrlResponce(bankId : String, accountId : String, viewId : String, otherAccountId: String, user : Box[User]) : JsonResponse =
        tryo{
            json.extract[OpenCorporatesUrlJSON]
          } match {
            case Full(openCorporatesUrlJSON) => {

              def addOpenCorporatesUrl(bankId : String, accountId : String, viewId : String, otherAccountId : String, user : Box[User], url : String): Box[Boolean] = {
                val addOpenCorporatesUrl = for {
                  metadata <- moderatedOtherAccountMetadata(bankId,accountId,viewId,otherAccountId,user)
                  openCorporatesUrl <- Box(metadata.openCorporatesUrl) ?~! {"view " + viewId + " does not authorize access to open_corporates_url"}
                  setImageUrl <- isFieldAlreadySet(openCorporatesUrl)
                  addOpenCorporatesUrl <- Box(metadata.addOpenCorporatesUrl) ?~ {"view " + viewId + " does not authorize adding open_corporates_url"}
                } yield addOpenCorporatesUrl

                addOpenCorporatesUrl.map(
                  func =>{
                    func(url)
                  }
                )
              }

              addOpenCorporatesUrl(bankId, accountId, viewId, otherAccountId, user, openCorporatesUrlJSON.open_corporates_url) match {
                case Full(posted) =>
                  if(posted)
                    JsonResponse(Extraction.decompose(SuccessMessage("open_corporates_url successfully saved")), Nil, Nil, 201)
                  else
                    JsonResponse(Extraction.decompose(ErrorMessage("open_corporates_url could not be saved")), Nil, Nil, 500)
                case Failure(msg, _, _) => JsonResponse(Extraction.decompose(ErrorMessage(msg)), Nil, Nil, 400)
                case _ => JsonResponse(Extraction.decompose(ErrorMessage("error")), Nil, Nil, 400)
              }
            }
            case _ => JsonResponse(Extraction.decompose(ErrorMessage("wrong JSON format")), Nil, Nil, 400)
          }


      if(isThereAnOAuthHeader)
      {
        val (httpCode, message, oAuthParameters) = validator("protectedResource", httpMethod)
        if(httpCode == 200)
        {
          val user = getUser(httpCode, oAuthParameters.get("oauth_token"))
          postOpenCorporatesUrlResponce(bankId, accountId, viewId, otherAccountId, user)
        }
        else
          JsonResponse(ErrorMessage(message), Nil, Nil, httpCode)
      }
      else
        postOpenCorporatesUrlResponce(bankId, accountId, viewId, otherAccountId, Empty)
    }
  })
  serve("obp" / "v1.1" prefix{
    case "banks" :: bankId :: "accounts" :: accountId :: viewId :: "other_accounts" :: otherAccountId :: "metadata" :: "open_corporates_url" :: Nil JsonPut json -> _ => {
      //log the API call
      logAPICall

      def updateOpenCorporatesURL(bankId : String, accountId : String, viewId : String, otherAccountId: String, user : Box[User]) : JsonResponse =
        tryo{
            json.extract[OpenCorporatesUrlJSON]
          } match {
            case Full(openCorporatesUrlJSON) => {

              def addOpenCorporatesUrl(bankId : String, accountId : String, viewId : String, otherAccountId : String, user : Box[User], url : String): Box[Boolean] = {
                val addOpenCorporatesUrl = for {
                  metadata <- moderatedOtherAccountMetadata(bankId,accountId,viewId,otherAccountId,user)
                  addOpenCorporatesUrl <- Box(metadata.addOpenCorporatesUrl) ?~ {"view " + viewId + " does not authorize adding open_corporates_url"}
                } yield addOpenCorporatesUrl

                addOpenCorporatesUrl.map(
                  func =>{
                    func(url)
                  }
                )
              }

              addOpenCorporatesUrl(bankId, accountId, viewId, otherAccountId, user, openCorporatesUrlJSON.open_corporates_url) match {
                case Full(posted) =>
                  if(posted)
                    JsonResponse(Extraction.decompose(SuccessMessage("open_corporates_url successfully saved")), Nil, Nil, 201)
                  else
                    JsonResponse(Extraction.decompose(ErrorMessage("open_corporates_url could not be saved")), Nil, Nil, 500)
                case Failure(msg, _, _) => JsonResponse(Extraction.decompose(ErrorMessage(msg)), Nil, Nil, 400)
                case _ => JsonResponse(Extraction.decompose(ErrorMessage("error")), Nil, Nil, 400)
              }
            }
            case _ => JsonResponse(Extraction.decompose(ErrorMessage("wrong JSON format")), Nil, Nil, 400)
          }


      if(isThereAnOAuthHeader)
      {
        val (httpCode, message, oAuthParameters) = validator("protectedResource", httpMethod)
        if(httpCode == 200)
        {
          val user = getUser(httpCode, oAuthParameters.get("oauth_token"))
          updateOpenCorporatesURL(bankId, accountId, viewId, otherAccountId, user)
        }
        else
          JsonResponse(ErrorMessage(message), Nil, Nil, httpCode)
      }
      else
        updateOpenCorporatesURL(bankId, accountId, viewId, otherAccountId, Empty)
    }
  })
  serve("obp" / "v1.1" prefix{
    case "banks" :: bankId :: "accounts" :: accountId :: viewId :: "other_accounts" :: otherAccountId :: "metadata" :: "corporate_location" :: Nil JsonPost json -> _ => {
      //log the API call
      logAPICall

      def postCorporateLocation(bankId : String, accountId : String, viewId : String, otherAccountId: String, user : Box[User]) : JsonResponse =
        tryo{
            json.extract[CorporateLocationJSON]
          } match {
            case Full(corporateLocationJSON) => {

              def addCorporateLocation(user : User, viewID : Long, longitude: Double, latitude : Double) : Box[Boolean] = {
                val addCorporateLocation = for {
                  metadata <- moderatedOtherAccountMetadata(bankId,accountId,viewId,otherAccountId,Full(user))
                  addCorporateLocation <- Box(metadata.addCorporateLocation) ?~ {"view " + viewId + " does not authorize adding corporate_location"}
                } yield addCorporateLocation

                addCorporateLocation.map(
                  func =>{
                    val datePosted = (now: TimeSpan)
                    func(user.id_, viewID, datePosted, longitude, latitude)
                  }
                )
              }
              val postedGeoTag = for {
                    u <- user ?~ "User not found. Authentication via OAuth is required"
                    view <- View.fromUrl(viewId) ?~ {"view " + viewId +" view not found"}
                    postedGeoTag <- addCorporateLocation(u, view.id, corporateLocationJSON.corporate_location.longitude, corporateLocationJSON.corporate_location.latitude)
                  } yield postedGeoTag

              postedGeoTag match {
                case Full(posted) =>
                  if(posted)
                    JsonResponse(Extraction.decompose(SuccessMessage("corporate_location successfully saved")), Nil, Nil, 201)
                  else
                    JsonResponse(Extraction.decompose(ErrorMessage("corporate_location could not be saved")), Nil, Nil, 500)
                case Failure(msg, _, _) => JsonResponse(Extraction.decompose(ErrorMessage(msg)), Nil, Nil, 400)
                case _ => JsonResponse(Extraction.decompose(ErrorMessage("error")), Nil, Nil, 400)
              }
            }
            case _ => JsonResponse(Extraction.decompose(ErrorMessage("wrong JSON format")), Nil, Nil, 400)
          }


      if(isThereAnOAuthHeader)
      {
        val (httpCode, message, oAuthParameters) = validator("protectedResource", httpMethod)
        if(httpCode == 200)
        {
          val user = getUser(httpCode, oAuthParameters.get("oauth_token"))
          postCorporateLocation(bankId, accountId, viewId, otherAccountId, user)
        }
        else
          JsonResponse(ErrorMessage(message), Nil, Nil, httpCode)
      }
      else
        JsonResponse(ErrorMessage("Authentication via OAuth is required"), Nil, Nil, 400)
    }
  })
  serve("obp" / "v1.1" prefix{
    case "banks" :: bankId :: "accounts" :: accountId :: viewId :: "other_accounts" :: otherAccountId :: "metadata" :: "corporate_location" :: Nil JsonPut json -> _ => {
      //log the API call
      logAPICall

      def postCorporateLocation(bankId : String, accountId : String, viewId : String, otherAccountId: String, user : Box[User]) : JsonResponse =
        tryo{
            json.extract[CorporateLocationJSON]
          } match {
            case Full(corporateLocationJSON) => {

              def addCorporateLocation(user : User, viewID : Long, longitude: Double, latitude : Double) : Box[Boolean] = {
                val addCorporateLocation = for {
                  metadata <- moderatedOtherAccountMetadata(bankId,accountId,viewId,otherAccountId,Full(user))
                  addCorporateLocation <- Box(metadata.addCorporateLocation) ?~ {"view " + viewId + " does not authorize adding corporate_location"}
                } yield addCorporateLocation

                addCorporateLocation.map(
                  func =>{
                    val datePosted = (now: TimeSpan)
                    func(user.id_, viewID, datePosted, longitude, latitude)
                  }
                )
              }
              val postedGeoTag = for {
                    u <- user ?~ "User not found. Authentication via OAuth is required"
                    view <- View.fromUrl(viewId) ?~ {"view " + viewId +" view not found"}
                    postedGeoTag <- addCorporateLocation(u, view.id, corporateLocationJSON.corporate_location.longitude, corporateLocationJSON.corporate_location.latitude)
                  } yield postedGeoTag

              postedGeoTag match {
                case Full(posted) =>
                  if(posted)
                    JsonResponse(Extraction.decompose(SuccessMessage("corporate_location successfully saved")), Nil, Nil, 201)
                  else
                    JsonResponse(Extraction.decompose(ErrorMessage("corporate_location could not be saved")), Nil, Nil, 500)
                case Failure(msg, _, _) => JsonResponse(Extraction.decompose(ErrorMessage(msg)), Nil, Nil, 400)
                case _ => JsonResponse(Extraction.decompose(ErrorMessage("error")), Nil, Nil, 400)
              }
            }
            case _ => JsonResponse(Extraction.decompose(ErrorMessage("wrong JSON format")), Nil, Nil, 400)
          }


      if(isThereAnOAuthHeader)
      {
        val (httpCode, message, oAuthParameters) = validator("protectedResource", httpMethod)
        if(httpCode == 200)
        {
          val user = getUser(httpCode, oAuthParameters.get("oauth_token"))
          postCorporateLocation(bankId, accountId, viewId, otherAccountId, user)
        }
        else
          JsonResponse(ErrorMessage(message), Nil, Nil, httpCode)
      }
      else
        JsonResponse(ErrorMessage("Authentication via OAuth is required"), Nil, Nil, 400)
    }
  })
}