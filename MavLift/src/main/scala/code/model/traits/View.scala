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


package code.model.traits
import code.snippet.CustomEditable
import net.liftweb.http.SHtml
import net.liftweb.json.JsonDSL._
import net.liftweb.json.JsonAST.JObject
import net.liftweb.common.Box
import net.liftweb.common.Empty
import net.liftweb.common.Full

class AliasType
class Alias extends AliasType
object Public extends Alias
object Private extends Alias
object NoAlias extends AliasType
case class AccountName(display: String, aliasType: AliasType)

trait View {
	  
  //e.g. "Anonymous", "Authorities", "Our Network", etc.
  def id: Long
  def name: String
  def description : String
  def permalink : String
  
  //the view settings 
  def usePrivateAliasIfOneExists: Boolean
  def usePublicAliasIfOneExists: Boolean
  
  //reading access

  //transaction fields
  def canSeeTransactionThisBankAccount : Boolean
  def canSeeTransactionOtherBankAccount : Boolean
  def canSeeTransactionMetadata : Boolean 
  def canSeeTransactionLabel: Boolean
  def canSeeTransactionAmount: Boolean
  def canSeeTransactionType: Boolean
  def canSeeTransactionCurrency: Boolean
  def canSeeTransactionStartDate: Boolean
  def canSeeTransactionFinishDate: Boolean
  def canSeeTransactionBalance: Boolean
  
  //transaction metadata
  def canSeeComments: Boolean
  def canSeeOwnerComment: Boolean

  //Bank account fields
  def canSeeBankAccountOwners : Boolean
  def canSeeBankAccountType : Boolean
  def canSeeBankAccountBalance : Boolean
  def canSeeBankAccountBalancePositiveOrNegative : Boolean
  def canSeeBankAccountCurrency : Boolean
  def canSeeBankAccountLabel : Boolean
  def canSeeBankAccountNationalIdentifier : Boolean
  def canSeeBankAccountSwift_bic : Boolean
  def canSeeBankAccountIban : Boolean
  def canSeeBankAccountNumber : Boolean
  def canSeeBankAccountName : Boolean

  //other bank account fields 
  def canSeeOtherAccountNationalIdentifier : Boolean 
  def canSeeSWIFT_BIC : Boolean
  def canSeeOtherAccountIBAN : Boolean
  def canSeeOtherAccountBankName : Boolean
  def canSeeOtherAccountNumber : Boolean
  def canSeeOtherAccountMetadata :Boolean

  //other bank account meta data
  def canSeeMoreInfo: Boolean
  def canSeeUrl: Boolean
  def canSeeImageUrl: Boolean
  def canSeeOpenCorporatesUrl: Boolean

  //writing access
  def canEditOwnerComment: Boolean
  def canAddComments : Boolean

  // In the future we can add a method here to allow someone to show only transactions over a certain limit
  
  def moderate(transaction: Transaction): ModeratedTransaction = {
    //transaction data
    val transactionId = transaction.id
    
    val thisBankAccount = 
    if(canSeeTransactionThisBankAccount)
    {
      val owners = if(canSeeBankAccountOwners) Some(transaction.thisAccount.owners) else None
      val accountType = if(canSeeBankAccountType) Some(transaction.thisAccount.accountType) else None
      val balance = if(canSeeBankAccountBalance) {
        transaction.thisAccount.balance.toString
      } else if (canSeeBankAccountBalancePositiveOrNegative) {
        if(transaction.thisAccount.balance.toString.startsWith("-")) "-" else "+"
      } else ""
      val currency = if(canSeeBankAccountCurrency) Some(transaction.thisAccount.currency) else None  
      val label = if(canSeeBankAccountLabel) Some(transaction.thisAccount.label) else None
      val number = if(canSeeBankAccountNumber) Some(transaction.thisAccount.number) else None
      val bankName = if(canSeeBankAccountName) Some(transaction.thisAccount.bankName) else None
      val nationalIdentifier = 
        if(canSeeBankAccountNationalIdentifier) 
          Some(transaction.thisAccount.nationalIdentifier) 
        else 
          None
      val swift_bic = 
        if(canSeeBankAccountSwift_bic) 
          Some(transaction.thisAccount.swift_bic) 
        else 
          None
      val iban = 
        if(canSeeBankAccountIban) 
          Some(transaction.thisAccount.iban) 
        else 
          None
      Some(new ModeratedBankAccount(transaction.thisAccount.id, owners, accountType, balance, currency, label,
      nationalIdentifier, swift_bic, iban, number, bankName))
    }
    else
      None

    val otherBankAccount = 
    if (canSeeTransactionOtherBankAccount) 
    {
      //other account data 
      var otherAccountId = transaction.otherAccount.id
      val otherAccountLabel: AccountName = 
      {
        val realName = transaction.otherAccount.label
        if (usePublicAliasIfOneExists) {

          val publicAlias = transaction.otherAccount.metadata.publicAlias

          if (! publicAlias.isEmpty ) AccountName(publicAlias, Public)
          else AccountName(realName, NoAlias)

        } else if (usePrivateAliasIfOneExists) {

          val privateAlias = transaction.otherAccount.metadata.privateAlias

          if (! privateAlias.isEmpty) AccountName(privateAlias, Private)
          else AccountName(realName, Private)
        } else 
          AccountName(realName, NoAlias)
      }
      val otherAccountNationalIdentifier = if (canSeeOtherAccountNationalIdentifier) Some(transaction.otherAccount.nationalIdentifier) else None
      val otherAccountSWIFT_BIC = if (canSeeSWIFT_BIC) Some(transaction.otherAccount.swift_bic) else None
      val otherAccountIBAN = if(canSeeOtherAccountIBAN) Some(transaction.otherAccount.iban) else None 
      val otherAccountBankName = if(canSeeOtherAccountBankName) Some(transaction.otherAccount.bankName) else None
      val otherAccountNumber = if(canSeeOtherAccountNumber) Some(transaction.otherAccount.number) else None
      val otherAccountMetadata = 
        if(canSeeOtherAccountMetadata) 
        {
          //other bank account metadata 
          val moreInfo = 
            if (canSeeMoreInfo) Some(transaction.otherAccount.metadata.moreInfo)
            else None
          val url = 
            if (canSeeUrl) Some(transaction.otherAccount.metadata.url)
            else None
          val imageUrl = 
            if (canSeeImageUrl) Some(transaction.otherAccount.metadata.imageUrl)
            else None
          val openCorporatesUrl = 
            if (canSeeOpenCorporatesUrl) Some(transaction.otherAccount.metadata.openCorporatesUrl)
            else None
          
          Some(new ModeratedOtherBankAccountMetadata(moreInfo, url, imageUrl, openCorporatesUrl))
        }
        else
            None

      Some(new ModeratedOtherBankAccount(otherAccountId,otherAccountLabel, otherAccountNationalIdentifier, 
        otherAccountSWIFT_BIC, otherAccountIBAN, otherAccountBankName, otherAccountNumber, otherAccountMetadata))
    }
    else  
      None
      
    //transation metadata
    val transactionMetadata = 
    if(canSeeTransactionMetadata)
    {
      val ownerComment = if (canSeeOwnerComment) transaction.metadata.ownerComment else None
      val comments = 
        if (canSeeComments)
          Some(transaction.metadata.comments.filter(comment => comment.viewId==id))
        else None
      val addCommentFunc= if(canAddComments) Some(transaction.metadata.addComment _) else None
      val addOwnerCommentFunc:Option[String=> Unit] = if (canEditOwnerComment) Some(transaction.metadata.ownerComment _) else None
      new Some(new ModeratedTransactionMetadata(ownerComment,comments,addOwnerCommentFunc,addCommentFunc))
    }
    else
      None

    val transactionType = 
      if (canSeeTransactionType) Some(transaction.transactionType)
      else None

    val transactionAmount = 
      if (canSeeTransactionAmount) Some(transaction.amount)
      else None

    val transactionCurrency = 
      if (canSeeTransactionCurrency) Some(transaction.currency)
      else None

    val transactionLabel = 
      if (canSeeTransactionLabel) Some(transaction.label)
      else None
    
    val transactionStartDate = 
      if (canSeeTransactionStartDate) Some(transaction.startDate)
      else None
    
    val transactionFinishDate = 
      if (canSeeTransactionFinishDate) Some(transaction.finishDate)
      else None

    val transactionBalance = 
      if (canSeeTransactionBalance) transaction.balance.toString()
      else ""

    new ModeratedTransaction(transactionId, thisBankAccount, otherBankAccount, transactionMetadata,
     transactionType, transactionAmount, transactionCurrency, transactionLabel, transactionStartDate,
      transactionFinishDate, transactionBalance)
  }
  
  def moderate(bankAccount: BankAccount) : Box[ModeratedBankAccount] = {
    if(bankAccount.allowAnnoymousAccess) {
      val owners : Set[AccountOwner] = if(canSeeBankAccountOwners) bankAccount.owners else Set()
      val balance = if(canSeeBankAccountBalance){
        bankAccount.balance.toString
      } else if(canSeeBankAccountBalancePositiveOrNegative) {
        if(bankAccount.balance.toString.startsWith("-")) "-" else "+"
      } else ""
      val accountType = if(canSeeBankAccountType) Some(bankAccount.accountType) else None
      val currency = if(canSeeBankAccountCurrency) Some(bankAccount.currency) else None
      val label = if(canSeeBankAccountLabel) Some(bankAccount.label) else None
      val nationalIdentifier = if(canSeeBankAccountNationalIdentifier) Some(bankAccount.label) else None
      val swiftBic = if(canSeeBankAccountSwift_bic) Some(bankAccount.swift_bic) else None
      val iban = if(canSeeBankAccountIban) Some(bankAccount.iban) else None
      val number = if(canSeeBankAccountNumber) Some(bankAccount.number) else None
      val bankName = if(canSeeBankAccountName) Some(bankAccount.bankName) else None
      
      Full(new ModeratedBankAccount(filteredId = bankAccount.id,
          							filteredOwners = Some(owners),
          							filteredAccountType = accountType,
          							filteredBalance = balance,
          							filteredCurrency = currency,
          							filteredLabel = label,
          							filteredNationalIdentifier = nationalIdentifier,
          							filteredSwift_bic = swiftBic,
          							filteredIban = iban,
          							filteredNumber = number,
          							filteredBankName = bankName
          							))
    }
    else Empty
  }
  
  def toJson : JObject = {
    ("name" -> name) ~
    ("description" -> description)
  }
  
}

//An implementation that has the least amount of permissions possible
class BaseView extends View {
  def id = 1
  def name = "Restricted"
  def permalink = "restricted"
  def description = ""
  
  //the view settings 
  def usePrivateAliasIfOneExists = true
  def usePublicAliasIfOneExists = true
  
  //reading access

  //transaction fields
  def canSeeTransactionThisBankAccount = false
  def canSeeTransactionOtherBankAccount = false
  def canSeeTransactionMetadata = false 
  def canSeeTransactionLabel = false
  def canSeeTransactionAmount = false
  def canSeeTransactionType = false
  def canSeeTransactionCurrency = false
  def canSeeTransactionStartDate = false
  def canSeeTransactionFinishDate = false
  def canSeeTransactionBalance = false
  
  //transaction metadata
  def canSeeComments = false
  def canSeeOwnerComment = false

  //Bank account fields
  def canSeeBankAccountOwners = false
  def canSeeBankAccountType = false
  def canSeeBankAccountBalance = false
  def canSeeBankAccountBalancePositiveOrNegative = false
  def canSeeBankAccountCurrency = false
  def canSeeBankAccountLabel = false
  def canSeeBankAccountNationalIdentifier = false
  def canSeeBankAccountSwift_bic = false
  def canSeeBankAccountIban = false
  def canSeeBankAccountNumber = false
  def canSeeBankAccountName = false

  //other bank account fields 
  def canSeeOtherAccountNationalIdentifier = false 
  def canSeeSWIFT_BIC = false
  def canSeeOtherAccountIBAN = false
  def canSeeOtherAccountBankName = false
  def canSeeOtherAccountNumber = false
  def canSeeOtherAccountMetadata = false

  //other bank account meta data
  def canSeeMoreInfo = false
  def canSeeUrl = false
  def canSeeImageUrl = false
  def canSeeOpenCorporatesUrl = false

  //writing access
  def canEditOwnerComment = false
  def canAddComments = false

}

class FullView extends View {
  def id = 2
  def name = "Full"
  def permalink ="full"
  def description = ""

  //the view settings 
  def usePrivateAliasIfOneExists = false
  def usePublicAliasIfOneExists = false
  
  //reading access

  //transaction fields
  def canSeeTransactionThisBankAccount = true
  def canSeeTransactionOtherBankAccount = true
  def canSeeTransactionMetadata = true 
  def canSeeTransactionLabel = true
  def canSeeTransactionAmount = true
  def canSeeTransactionType = true
  def canSeeTransactionCurrency = true
  def canSeeTransactionStartDate = true
  def canSeeTransactionFinishDate = true
  def canSeeTransactionBalance = true
  
  //transaction metadata
  def canSeeComments = true
  def canSeeOwnerComment = true

  //Bank account fields
  def canSeeBankAccountOwners = true
  def canSeeBankAccountType = true
  def canSeeBankAccountBalance = true
  def canSeeBankAccountBalancePositiveOrNegative = true
  def canSeeBankAccountCurrency = true
  def canSeeBankAccountLabel = true
  def canSeeBankAccountNationalIdentifier = true
  def canSeeBankAccountSwift_bic = true
  def canSeeBankAccountIban = true
  def canSeeBankAccountNumber = true
  def canSeeBankAccountName = true

  //other bank account fields 
  def canSeeOtherAccountNationalIdentifier = true 
  def canSeeSWIFT_BIC = true
  def canSeeOtherAccountIBAN = true
  def canSeeOtherAccountMetadata = true
  def canSeeOtherAccountBankName = true
  def canSeeOtherAccountNumber = true

  //other bank account meta data
  def canSeeMoreInfo = true
  def canSeeUrl = true
  def canSeeImageUrl = true
  def canSeeOpenCorporatesUrl = true

  //writing access
  def canEditOwnerComment = true
  def canAddComments = true

}


