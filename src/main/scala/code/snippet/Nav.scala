/**
 * Open Bank Project - Transparency / Social Finance Web Application
 * Copyright (C) 2011 - 2015, TESOBE  Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Email: contact@tesobe.com
 * TESOBE Ltd.
 * Osloer Str. 16/17
 * Berlin 13359, Germany
 *
 * This product includes software developed at
 * TESOBE (http://www.tesobe.com/)
 * by
 * Simon Redfern : simon AT tesobe DOT com
 * Stefan Bethge : stefan AT tesobe DOT com
 * Everett Sochowski : everett AT tesobe DOT com
 * Ayoub Benali: ayoub AT tesobe DOT com
 *
 */

package code.snippet

import net.liftweb.http.S
import net.liftweb.http.LiftRules
import net.liftweb.util.Helpers._
import net.liftweb.sitemap.Loc
import net.liftweb.common.Box
import net.liftweb.common.Full
import net.liftweb.common.Empty
import net.liftweb.sitemap.SiteMapSingleton
import code.lib.ObpJson._
import code.lib.ObpAPI
import net.liftweb.util.CssSel


class Nav {

  val url = S.uri.split("/", 0)
  val accountJson : Option[AccountJson]= {
    if (url.size > 4) {

      val viewId = "owner"  //if we can't access the owner view, account returns nothing
      val bankId = url( url.indexOf("banks")+1 )
      val accountId = url( url.indexOf("accounts")+1 )

      ObpAPI.getAccount(bankId, accountId, viewId)
    } else {
      None
    }
  }
  val viewJsons: List[ViewJson] = {
    accountJson.flatMap(accJson => {
      accJson.views_available
    }).toList.flatten
  }

  def eraseMenu =
    "* * " #> ""


  def views: net.liftweb.util.CssSel = {
    val url = S.uri.split("/", 0)
    if (url.size > 4) {
      ".navitem *" #> {
        viewJsons.map(viewJson => {
          val viewUrl = "/banks/" + url(2) + "/accounts/" + url(4) + "/" + viewJson.id.getOrElse("")
          ".navlink [href]" #> viewUrl &
          ".navlink *" #> viewJson.short_name.getOrElse("") &
          ".navlink [class+]" #> markIfSelected(viewUrl)
        })
      }
    } else
      eraseMenu
  }

  def management = {
    
    val url = S.uri.split("/", 0)

    // Menu for a page which lists counterparties and their metadata (and edits the metadata)
    def getManagement = {
      val views = accountJson.flatMap(_.views_available).toList.flatten
      //TODO: Determine this in a better way
      val hasOwnerPermissions = views.exists(v => v.id == Some("owner"))
      
      if (hasOwnerPermissions) {
        val managementUrl = "/banks/" + url(2) + "/accounts/" + url(4) + "/management"
        Some(".navlink [href]" #> { managementUrl } &
        ".navlink *" #> "Counterparties" &
        ".navlink [class+]" #> markIfSelected(managementUrl))
      } else None
    }

    if (url.size > 4) getManagement getOrElse eraseMenu
    else eraseMenu
  }


  // Menu For Entitlements / permissions on an account / view
  def editViews : CssSel = {
    val views = accountJson.flatMap(_.views_available).toList.flatten
    val hasOwnerPermissions = views.exists(v => v.id == Some("owner"))
    
    if(hasOwnerPermissions) {
      val editViewsUrl = "/banks/" + url(2) + "/accounts/" + url(4) + "/views/list"
      ".navlink [href]" #> { editViewsUrl } &
      ".navlink *" #> "Views" &
      ".navlink [class+]" #> markIfSelected(editViewsUrl)
    } else eraseMenu
  }

  def item = {
    val name = S.attr("name").getOrElse("")
    val loc = (for {
      sitemap <- LiftRules.siteMap
      l <- new SiteMapSingleton().findAndTestLoc(name)
    } yield l)

    ".navitem *" #> {
      loc.map(navItemSelector)
    }
  }

  def navItemSelector(l: Loc[_]) = {
    ".navlink [href]" #> l.calcDefaultHref &
      ".navlink *" #> l.linkText &
      ".navlink [class+]" #> markIfSelected(l.calcDefaultHref)
  }


  // Menu for which Users have access to which Views
  def privilegeAdmin = {
    val url = S.uri.split("/", 0)

    def getPrivilegeAdmin = {
      val views = accountJson.flatMap(_.views_available).toList.flatten
      //TODO: Determine this in a better way
      val hasOwnerPermissions = views.exists(v => v.id == Some("owner"))
      
      if (hasOwnerPermissions) {
        val permissionsUrls = "/banks/" + url(2) + "/accounts/" + url(4) + "/permissions"
        Some(".navitem *" #> {
        ".navlink [href]" #> permissionsUrls &
          ".navlink *" #> "Users" &
          ".navlink [class+]" #> markIfSelected(permissionsUrls)
        })
      } else None
    }
    
    def hide = ".navitem *" #> ""

    if (url.size > 4) getPrivilegeAdmin.getOrElse(hide) 
    else hide
  }

  def markIfSelected(href: String): Box[String] = {
    val currentHref = S.uri
    if (href.equals(currentHref)) Full("selected")
    else Empty
  }

}