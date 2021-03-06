package code.snippet

import code.util.Helper._
import net.liftweb.http.js.JE.{Call, Str}
import net.liftweb.http.js.JsCmd
import net.liftweb.util.Helpers._
import scala.xml.{Node, NodeSeq, Text}
import code.lib.ObpJson.CompleteViewJson
import net.liftweb.util.CssSel
import net.liftweb.http.{S, SHtml}
import net.liftweb.json.JsonAST.JValue
import net.liftweb.json._
import net.liftweb.http.js.JsCmds.{SetHtml, Alert, RedirectTo}
import net.liftweb.common.{Full, Loggable, Box}
import code.lib.ObpAPI
import net.liftweb.http.SHtml.{text,ajaxSubmit, ajaxButton}
import ObpAPI.{addView, deleteView, updateAccountLabel, getAccount}
import SHtml._

case class ViewUpdateData(
  viewId: String,
  updateJson: JValue
)

case class ViewsDataJSON(
   views: List[CompleteViewJson],
   bankId: String,
   accountId: String
)

/*
For maintaining permissions on the views (entitlements on the account)
 */
class ViewsOverview(viewsDataJson: ViewsDataJSON) extends Loggable {
  val views = viewsDataJson.views
  val bankId = viewsDataJson.bankId
  val accountId = viewsDataJson.accountId

  def setAccountTitle = ".account_title *" #> getAccountTitle(bankId, accountId)

  def getTableContent(xhtml: NodeSeq) :NodeSeq = {

    //add ajax callback to save view
    def saveOnClick(viewId : String): CssSel = {
      implicit val formats = DefaultFormats

      ".save-button [data-id]" #> viewId &
      ".save-button [onclick+]" #> SHtml.ajaxCall(Call("collectData", Str(viewId)), callResult => {
        val result: Box[Unit] = for {
          data <- tryo{parse(callResult).extract[ViewUpdateData]}
          response <- ObpAPI.updateView(viewsDataJson.bankId, viewsDataJson.accountId, viewId, data.updateJson)
        } yield{
          response
        }
        if(result.isDefined) {
          val msg = "View " + viewId + " has been updated"
          Call("socialFinanceNotifications.notify", msg).cmd
        }
        else {
          val msg = "Error updating view"
          Call("socialFinanceNotifications.notifyError", msg).cmd
        }
      })
    }

    def deleteOnClick(viewId : String): CssSel = {
      ".delete-button [data-id]" #> viewId &
        ".delete-button [onclick+]" #> SHtml.ajaxCall(Call("collectData", Str(viewId)), callResult => {
          val result = ObpAPI.deleteView(viewsDataJson.bankId, viewsDataJson.accountId, viewId)

          if(result) {
            val msg = "View " + viewId + " has been deleted"
            Call("socialFinanceNotifications.notify", msg).cmd
            RedirectTo("")
          }
          else {
            val msg = "Error deleting view"
            Call("socialFinanceNotifications.notifyError", msg).cmd
          }
        })
    }

    val permissionsCollection: List[Map[String, Boolean]] = views.map(view => view.permissions)


    // Use permissions from the first view as a basis for permission names.
    val permissions: Map[String, Boolean] = permissionsCollection(0)

    def aliasType(typeInJson : Option[String]) = {
      typeInJson match {
        case Some("") => "none (display real names only)"
        case Some(s) => s
        case _ => ""
      }
    }
    
    val ids = getIds()
    val viewNameSel     = ".view_name *" #> views.map( view => view.shortName.getOrElse(""))
    val shortNamesSel   = ".short_name"  #> views.map( view => "* *" #> view.shortName.getOrElse("") & "* [data-viewid]" #> view.id )
    val aliasSel        = ".alias"       #> views.map( view => "* *" #> aliasType(view.alias) & "* [data-viewid]" #> view.id )
    val descriptionSel  = ".description" #> views.map( view => ".desc *" #> view.description.getOrElse("") & "* [data-viewid]" #> view.id )
    val isPublicSel     = ".is_public *" #> getIfIsPublic()
    val addEditSel      = ".edit"        #> ids.map(x => "* [data-id]" #> x)
    val addSaveSel      = ".save"        #> ids.map(x => ("* [data-id]" #> x) & deleteOnClick(x) & saveOnClick(x))
    val addCancelSel    = ".cancel"      #> ids.map(x => "* [data-id]" #> x)


    val permissionNames = permissions.keys
    val permSel = ".permissions *" #>
      permissionNames.map(
        permName => {
          ".permission_name *"  #> permName.capitalize.replace("_", " ") &
          ".permission_value *" #> getPermissionValues(permName)
        }
      )
      (viewNameSel &
        shortNamesSel &
        aliasSel &
        descriptionSel &
        isPublicSel &
        permSel &
        addEditSel &
        addSaveSel &
        addCancelSel
      ).apply(xhtml)
  }

  def getIds(): List[String] = {
    views.map( view => view.id.getOrElse(""))
  }


  def getIfIsPublic() :List[CssSel] = {
    views.map(
      view => {
        val isPublic = view.isPublic.getOrElse(false)
        val viewId: String = view.id.getOrElse("")
        val checked =
          if(isPublic)
            ".is_public_cb [checked]" #> "checked" &
            ".is_public_cb [disabled]" #> "disabled"
        else
            ".is_public_cb [disabled]" #> "disabled"

        val checkBox =
          checked &
            ".is_public_cb [data-viewid]" #> viewId

        checkBox
      }
    )
  }

  def getPermissionValues(permName: String) :List[CssSel] = {
    views.map(
      view => {
        val permValue: Boolean = view.permissions(permName)
        val viewId: String = view.id.getOrElse("")
        val checked =
          if(permValue){
            ".permission_value_cb [checked]" #> "checked" &
            ".permission_value_cb [disabled]" #> "disabled"
          }
          else
            ".permission_value_cb [disabled]" #> "disabled"

        val checkBox =
          checked &
          ".permission_value_cb [value]" #> permName &
          ".permission_value_cb [name]" #> permName &
          ".permission_value_cb [data-viewid]" #> viewId

        checkBox
      }
    )
  }

  //set up ajax handlers to add a new view
  def setupAddView(xhtml: NodeSeq): NodeSeq = {
    var newViewName = ""

    def process(): JsCmd = {
      logger.debug(s"ViewsOverview.setupAddView.process: create view called $newViewName")

      if (views.find { case (v) => v.shortName.get == newViewName }.isDefined) {
        val msg = "Sorry, a View with that name already exists."
        Call("socialFinanceNotifications.notifyError", msg).cmd
      } else {
        // This only adds the view (does not grant the current user access)
        val result = addView(bankId, accountId, newViewName)

        if (result.isDefined) {
          val msg = "View " + newViewName + " has been created. Please use the Access tab to grant yourself or others access."
          Call("socialFinanceNotifications.notify", msg).cmd
          // After creation, current user does not have access so, we show message above.
          // TODO: Redirect to a page where user can give him/her self access - and/or grant access automatically.
          // For now, don't reload so user can see the message above // reload page for new view to be shown // RedirectTo("")
        } else {
          val msg = "View " + newViewName + " could not be created"
          Call("socialFinanceNotifications.notifyError", msg).cmd
        }
      }
    }

    (
      // Bind newViewName field to variable (e.g. http://chimera.labs.oreilly.com/books/1234000000030/ch03.html)
      "@new_view_name" #> text(newViewName, s => newViewName = s) &
      // Replace the type=submit with Javascript that makes the ajax call.
      "type=submit" #> ajaxSubmit("OK", process)
    ).apply(xhtml)
  }

  //set up ajax handlers to edit account label
  def setupEditLabel(xhtml: NodeSeq): NodeSeq = {
    var newLabel = ""

    def process(): JsCmd = {
      logger.debug(s"ViewsOverview.setupEditLabel.process: edit label $newLabel")
      val result = updateAccountLabel(bankId, accountId, newLabel)
      if (result.isDefined) {
        val msg = "Label " + newLabel + " has been set"
        Call("socialFinanceNotifications.notify", msg).cmd
        // So we can see the new account title which may use the updated label
        RedirectTo("")
      } else {
         val msg = "Sorry, Label" + newLabel + " could not be set ("+ result +")"
         Call("socialFinanceNotifications.notifyError", msg).cmd
      }
    }

    val label = getAccount(bankId, accountId, "owner").get.label.getOrElse("Label")
    (
      // Bind newViewName field to variable (e.g. http://chimera.labs.oreilly.com/books/1234000000030/ch03.html)
      "@new_label" #> text(newLabel, s => newLabel = s) &
        // Replace the type=submit with Javascript that makes the ajax call.
        "type=submit" #> ajaxSubmit("OK", process) &
        "type=text [value]" #> label
      ).apply(xhtml)
  }
}
