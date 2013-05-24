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
package code.model

import scala.math.BigDecimal
import java.util.Date
import scala.collection.immutable.Set
import net.liftweb.json.JsonDSL._
import sun.reflect.generics.reflectiveObjects.NotImplementedException
import net.liftweb.json.JObject
import net.liftweb.json.JsonDSL._
import net.liftweb.json.JsonAST.JArray
import net.liftweb.common._
import code.model.dataAccess.{LocalStorage, Account, HostedBank}
import code.model.dataAccess.OBPEnvelope.OBPQueryParam


class Bank(
  val id: String,
  val shortName : String,
  val fullName : String,
  val permalink : String,
  val logoURL : String,
  val website : String
)
{
  def accounts = LocalStorage.getBankAccounts(this)
  def publicAccounts = LocalStorage.getPublicBankAccounts(this)
  def nonPublicAccounts(user : User) : List[BankAccount] = {
    LocalStorage.getNonPublicBankAccounts(user, id)
  }

  def detailedJson : JObject = {
    ("name" -> shortName) ~
    ("website" -> "") ~
    ("email" -> "")
  }

  def toJson : JObject = {
    ("alias" -> permalink) ~
      ("name" -> shortName) ~
      ("logo" -> "") ~
      ("links" -> linkJson)
  }

  def linkJson : JObject = {
    ("rel" -> "bank") ~
    ("href" -> {"/" + permalink + "/bank"}) ~
    ("method" -> "GET") ~
    ("title" -> {"Get information about the bank identified by " + permalink})
  }
}

object Bank {
  def apply(bankPermalink: String) : Box[Bank] = LocalStorage.getBank(bankPermalink)

  def all : List[Bank] = LocalStorage.allBanks

  def toJson(banks: Seq[Bank]) : JArray =
    banks.map(bank => bank.toJson)

}

class AccountOwner(
  val id : String,
  val name : String
)

class BankAccount(
  val id : String,
  val owners : Set[AccountOwner],
  val accountType : String,
  val balance : BigDecimal,
  val currency : String,
  val name : String,
  val label : String,
  val nationalIdentifier : String,
  val swift_bic : Option[String],
  val iban : Option[String],
  val allowAnnoymousAccess : Boolean,
  val number : String,
  val bankName : String,
  val bankPermalink : String,
  val permalink : String
) extends Loggable{

  def permittedViews(user: Box[User]) : Set[View] = {
    user match {
      case Full(u) => u.permittedViews(this)
      case _ =>{
        logger.info("no user was found in the permittedViews")
        if(this.allowPublicAccess) Set(Public) else Set()
      }
    }
  }

  def transactions(from: Date, to: Date): Set[Transaction] = {
    throw new NotImplementedException
  }

  def transaction(id: String): Box[Transaction] = {
    LocalStorage.getTransaction(id, bankPermalink, permalink)
  }
  def allowPublicAccess = allowAnnoymousAccess

  def getModeratedTransactions(moderate: Transaction => ModeratedTransaction): List[ModeratedTransaction] = {
   LocalStorage.getModeratedTransactions(permalink, bankPermalink)(moderate)
  }

  def getModeratedTransactions(queryParams: OBPQueryParam*)(moderate: Transaction => ModeratedTransaction): List[ModeratedTransaction] = {
    LocalStorage.getModeratedTransactions(permalink, bankPermalink, queryParams: _*)(moderate)
  }

  def getTransactions(queryParams: OBPQueryParam*) : Box[List[Transaction]] = {
    LocalStorage.getTransactions(permalink, bankPermalink, queryParams: _*)
  }

  def getTransactions(bank: String, account: String): Box[List[Transaction]] = {
   LocalStorage.getTransactions(permalink, bankPermalink)
  }

  def moderatedTransaction(id: String, view: View, user: Box[User]) : Box[ModeratedTransaction] = {
    if(authorizedAccess(view, user)) {
      transaction(id).map(view.moderate)
    } else Failure("view/transaction not authorized")
  }

  def moderatedBankAccount(view: View, user: Box[User]) : Box[ModeratedBankAccount] = {
    if(authorizedAccess(view, user)){
      view.moderate(this) match {
        case Some(thisBankAccount) => Full(thisBankAccount)
        case _ => Failure("could not moderate this bank account id " + id, Empty, Empty)
      }
    }
    else
      Failure("user not allowed to access the " + view.name + " view.", Empty, Empty)
  }

  /**
  * @param a user requesting to see the other users' permissions
  * @return a Box of all the users' permissions of this bank account if the user passed as a parameter has access to the owner view (allowed to see this kind of data)
  */
  def permissions(user : User) : Box[List[Permission]] = {
    //check if the user have access to the owner view in this the account
    if(authorizedAccess(Owner,Full(user)))
      LocalStorage.permissions(this)
    else
      Failure("user : " + user.emailAddress + "don't have access to owner view on account " + id, Empty, Empty)
  }

  /**
  * @param a user that want to grant an other user access to a view
  * @param the id of the view that we want to grant access
  * @param the id of the other user that we want grant access
  * @return a Full(true) if everything is okay, a Failure otherwise
  */
  def addPermission(user : User, viewId : String, otherUserId : String) : Box[Boolean] = {
    //check if the user have access to the owner view in this the account
    if(authorizedAccess(Owner,Full(user)))
      for{
        view <- View.fromUrl(viewId) //check if the viewId corresponds to a view
        otherUser <- User.findById(otherUserId) //check if the userId corresponds to a user
        isSaved <- LocalStorage.addPermission(id, view, otherUser) ?~ "could not save the privilege"
      } yield isSaved
    else
      Failure("user : " + user.emailAddress + "don't have access to owner view on account " + id, Empty, Empty)
  }

  /**
  * @param a user that want to revoke an other user access to a view
  * @param the id of the view that we want to revoke access
  * @param the id of the other user that we want revoke access
  * @return a Full(true) if everything is okay, a Failure otherwise
  */
  def revokePermission(user : User, viewId : String, otherUserId : String) : Box[Boolean] = {
    //check if the user have access to the owner view in this the account
    if(authorizedAccess(Owner,Full(user)))
      for{
        view <- View.fromUrl(viewId) //check if the viewId corresponds to a view
        otherUser <- User.findById(otherUserId) //check if the userId corresponds to a user
        isRevoked <- LocalStorage.revokePermission(id, view, otherUser) ?~ "could not revoke the privilege"
      } yield isRevoked
    else
      Failure("user : " + user.emailAddress + " don't have access to owner view on account " + id, Empty, Empty)
  }

  /**
  * @param the view that we want test the access to
  * @param the user that we want to see if he has access to the view or not
  * @return true if the user is allowed to access this view, false otherwise
  */
  def authorizedAccess(view: View, user: Option[User]) : Boolean = {

    view match {
      case Public => allowPublicAccess
      case _ => user match {
        case Some(u) => {
          u.permittedViews(this).contains(view)
        }
        case None => false
      }
    }
  }

  /**
  * @param the view that we will use to get the ModeratedOtherBankAccount list
  * @param the user that want access to the ModeratedOtherBankAccount list
  * @return a Box of a list ModeratedOtherBankAccounts, it the bank
  *  accounts that have at least one transaction in common with this bank account
  */
  def moderatedOtherBankAccounts(view : View, user : Box[User]) : Box[List[ModeratedOtherBankAccount]] = {
    if(authorizedAccess(view, user)){
      LocalStorage.getOthersAccount(id) match {
        case Full(otherbankAccounts) => Full(otherbankAccounts.map(view.moderate).collect{case Some(t) => t})
        case Failure(msg, _, _) => Failure(msg, Empty, Empty)
        case _ => Empty
      }
    }
    else
      Failure("user not allowed to access the " + view.name + " view.", Empty, Empty)
  }
  /**
  * @param the ID of the other bank account that the user want have access
  * @param the view that we will use to get the ModeratedOtherBankAccount
  * @param the user that want access to the otherBankAccounts list
  * @return a Box of a ModeratedOtherBankAccounts, it a bank
  *  account that have at least one transaction in common with this bank account
  */
  def moderatedOtherBankAccount(otherAccountID : String, view : View, user : Box[User]) : Box[ModeratedOtherBankAccount] =
    if(authorizedAccess(view, user)) {
      LocalStorage.getOtherAccount(id, otherAccountID) match {
        case Full(otherbankAccount) =>
          view.moderate(otherbankAccount) match {
            case Some(otherAccount) => Full(otherAccount)
            case _ => Failure("could not moderate the other account id " + otherAccountID)
          }
        case Failure(msg, _, _) => Failure(msg, Empty, Empty)
        case _ => Empty
      }
    }
    else
      Failure("user not allowed to access the " + view.name + " view.", Empty, Empty)

  def overviewJson(user: Box[User]): JObject = {
    val views = permittedViews(user)
    ("number" -> number) ~
    ("account_alias" -> label) ~
    ("owner_description" -> "") ~
    ("views_available" -> views.map(view => view.toJson)) ~
    View.linksJson(views, permalink, bankPermalink)
  }
}

object BankAccount {
  def apply(bankpermalink: String, bankAccountPermalink: String) : Box[BankAccount] = {
    LocalStorage.getAccount(bankpermalink, bankAccountPermalink) match {
      case Full(account) => Full(Account.toBankAccount(account))
      case _ => Failure("account " + bankAccountPermalink +" not found in bank " + bankpermalink, Empty, Empty)
    }
  }

  def all : List[BankAccount] = {
    LocalStorage.getAllAccounts()
  }

  def publicAccounts : List[BankAccount] = {
    LocalStorage.getAllPublicAccounts()
  }
}

class OtherBankAccount(
  val id : String,
  val label : String,
  val nationalIdentifier : String,
  //the bank international identifier
  val swift_bic : Option[String],
  //the international account identifier
  val iban : Option[String],
  val number : String,
  val bankName : String,
  val metadata : OtherBankAccountMetadata,
  val kind : String
)

class Transaction(
  //A universally unique id
  val uuid : String,
  //The bank's id for the transaction
  val id : String,
  val thisAccount : BankAccount,
  val otherAccount : OtherBankAccount,
  val metadata : TransactionMetadata,
  //E.g. cash withdrawal, electronic payment, etc.
  val transactionType : String,
  val amount : BigDecimal,
  //ISO 4217, e.g. EUR, GBP, USD, etc.
  val currency : String,
  // Bank provided comment
  val label : Option[String],
  // The date the transaction was initiated
  val startDate : Date,
  // The date when the money finished changing hands
  val finishDate : Date,
  //the new balance for the bank account
  val balance :  BigDecimal
)