/*
 * Copyright 2015 Brady Wood, Branko Juric
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package gwen.web

import com.typesafe.scalalogging.LazyLogging
import gwen.Predefs.Kestrel
import gwen.web.errors.{LocatorBindingException, unsupportedModifierKeyError, waitTimeoutError}
import org.apache.commons.io.FileUtils
import org.openqa.selenium._
import org.openqa.selenium.interactions.Actions
import org.openqa.selenium.support.ui.{Select, WebDriverWait}

import scala.collection.JavaConverters._
import scala.util.Try

/**
  * The web context. All web driver interactions happen here (and will do nothing when --dry-run is enabled).
  */
class WebContext(env: WebEnvContext) extends WebElementLocator with LazyLogging {

  /** The web driver manager. */
  private val driverManager = new DriverManager()

  /** Last captured screenshot file size. */
  private var lastScreenshotSize: Option[Long] = None

  /** Resets the driver context. */
  def reset() {
    driverManager.reset()
    lastScreenshotSize = None
  }

  /** Closes all browsers and associated web drivers (if any have loaded). */
  def close(): Unit = {
    env.perform {
      driverManager.quit()
    }
  }

  /** Closes a named browser and associated web driver. */
  def close(name: String): Unit = {
    env.perform {
      driverManager.quit(name)
    }
  }

  /**
    * Invokes a function that performs an operation on the current web driver
    * session and conditionally captures the current screenshot if the specified
    * takeScreenShot is true.
    *
    * @param function the function to perform
    * @param takeScreenShot true to take screenshot after performing the function
    */
  def withWebDriver[T](function: WebDriver => T)(implicit takeScreenShot: Boolean = false): Option[T] = {
    env.evaluate(None.asInstanceOf[Option[T]]) {
      driverManager.withWebDriver { driver =>
        Option(function(driver)) tap { _ =>
          if (takeScreenShot) {
            captureScreenshot(false)
          }
        }
      }
    }
  }

  /**
    * Gets a web element binding.
    *
    * @param element the name of the web element
    */
  def getLocatorBinding(element: String): LocatorBinding = env.getLocatorBinding(element)

  /**
    * Gets a cached web element.
    *
    * @param element the name of the web element
    * @return the cached web element
    */
  def getCachedWebElement(element: String): Option[WebElement] = env.featureScope.getObject(element) match {
    case Some(we: WebElement) =>
      highlightElement(we)
      Some(we)
    case _ => None
  }

  /**
    * Performs a function on a web element and transparently re-locates elements and
    * re-attempts the function if the web driver throws an exception.
    *
    * @param action the action string
    * @param elementBinding the web element locator binding
    * @param f the function to perform on the element
    */
  private def withWebElement[T](action: String, elementBinding: LocatorBinding)(f: WebElement => T): Option[T] =
     withWebElement(Some(action), elementBinding)(f)

  /**
    * Performs a function on a web element and transparently re-locates elements and
    * re-attempts the function if the web driver throws an exception.
    *
    * @param elementBinding the web element locator binding
    * @param f the function to perform on the element
    */
  private def withWebElement[T](elementBinding: LocatorBinding)(f: WebElement => T): Option[T] =
     withWebElement(None, elementBinding)(f)

  /**
    * Locates a web element and performs a operation on it.
    *
    * @param action optional action string
    * @param elementBinding the web element locator binding
    * @param operation the operation to perform on the element
    */
  private def withWebElement[T](action: Option[String], elementBinding: LocatorBinding)(operation: WebElement => T): Option[T] =
    env.evaluate(None.asInstanceOf[Option[T]]) {
      val locator = elementBinding.locators.head
      val wHandle = locator.container.flatMap(_ => withWebDriver(_.getWindowHandle))
      try {
        val webElement = locate(elementBinding)
        action.foreach { actionString =>
          logger.debug(s"${actionString match {
            case "click" => "Clicking"
            case "right click" => "Right clicking"
            case "right click and send keys" => "Right clicking and sending keys"
            case "submit" => "Submitting"
            case "check" => "Checking"
            case "uncheck" => "Unchecking"
            case "send keys" => "Sending keys"
           }} ${elementBinding.element}")
        }
        Option {
          try {
            operation(webElement)
          } catch {
            case _: StaleElementReferenceException =>
              Thread.sleep(WebSettings.`gwen.web.throttle.msecs`)
              try {
                operation(locate(elementBinding))
              } catch {
                case _: StaleElementReferenceException =>
                  Thread.sleep(WebSettings.`gwen.web.throttle.msecs`)
                  operation(locate(elementBinding))
              }
          }
        } tap { _ =>
          if (WebSettings.`gwen.web.capture.screenshots`) {
            captureScreenshot(false)
          }
        }
      } finally {
        wHandle foreach { handle =>
          withWebDriver { driver =>
            driver.switchTo().window(handle)
          }
        }
      }
    }

  /** Captures and the current screenshot and adds it to the attachments list. */
  def captureScreenshot(unconditional: Boolean): Unit =
    env.perform {
      Thread.sleep(WebSettings.`gwen.web.throttle.msecs` / 2)
      val screenshot = driverManager.withWebDriver { driver =>
        driver.asInstanceOf[TakesScreenshot].getScreenshotAs(OutputType.FILE)
      }
      val keep = unconditional || WebSettings.`gwen.web.capture.screenshots.duplicates` || lastScreenshotSize.fold(true) { _ != screenshot.length}
      if (keep) {
        if (!WebSettings.`gwen.web.capture.screenshots.duplicates`) lastScreenshotSize = Some(screenshot.length())
        env.addAttachment("Screenshot", screenshot.getName.substring(screenshot.getName.lastIndexOf('.') + 1), null) tap {
          case (_, file) => FileUtils.copyFile(screenshot, file)
        }
      }
    }

  /**
    * Injects and executes a javascript on the current page through web driver.
    *
    * @param javascript the script expression to execute
    * @param params optional parameters to the script
    * @param takeScreenShot true to take screenshot after performing the function
    */
  def executeJS(javascript: String, params: Any*)(implicit takeScreenShot: Boolean = false): Any =
    withWebDriver { webDriver =>
      webDriver.asInstanceOf[JavascriptExecutor].executeScript(javascript, params.map(_.asInstanceOf[AnyRef]) : _*) tap { result =>
        if (takeScreenShot && WebSettings.`gwen.web.capture.screenshots`) {
          captureScreenshot(false)
        }
        logger.debug(s"Evaluated javascript: $javascript, result='$result'")
        if (result.isInstanceOf[Boolean] && result.asInstanceOf[Boolean]) {
          Thread.sleep(WebSettings.`gwen.web.throttle.msecs`)
        }
      }
    } getOrElse s"$$[javascript:$javascript]"

  /**
    * Waits for a given condition to be true. Errors on time out
    * after "gwen.web.wait.seconds" (default is 10 seconds)
    *
    * @param reason the reason for waiting (used to report timeout error)
    * @param condition the boolean condition to wait for (until true)
    */
  def waitUntil(reason: String)(condition: => Boolean): Unit = {
    waitUntil(reason, WebSettings.`gwen.web.wait.seconds`) { condition }
  }

  /**
    * Waits until a given condition is ready for a given number of seconds.
    * Errors on given timeout out seconds.
    *
    * @param reason reason for waiting (used to report timeout error)
    * @param timeoutSecs the number of seconds to wait before timing out
    * @param condition the boolean condition to wait for (until true)
    */
  def waitUntil(reason: String, timeoutSecs: Long)(condition: => Boolean): Unit = {
    def doWaitUntil(webDriver: WebDriver, timeout: Long) {
      new WebDriverWait(webDriver, timeout).until {
        (_: WebDriver) => condition
      }
    }
    // some drivers intermittently throw javascript errors, so have to track timeout and retry
    withWebDriver { webDriver =>
      val start = System.nanoTime()
      var timeout = timeoutSecs
      while (timeout > -1) {
        try {
          doWaitUntil(webDriver, timeout)
          timeout = -1
        } catch {
          case e: TimeoutException =>
            waitTimeoutError(reason)
          case e: WebDriverException =>
            Thread.sleep(WebSettings`gwen.web.throttle.msecs`)
            timeout = timeoutSecs - ((System.nanoTime() - start) / 1000000000L)
            if (timeout <= 0) throw e
        }
      }
    }
  }

  /**
    * Highlights and then un-highlights a browser element.
    * Uses pure javascript, as suggested by https://github.com/alp82.
    * The duration of the highlight lasts for `gwen.web.throttle.msecs`.
    * The look and feel of the highlight is controlled by the
    * `gwen.web.highlight.style` setting.
    *
    * @param element the element to highlight
    */
  def highlightElement(element: WebElement): Unit = {
    env.perform {
      val msecs = WebSettings`gwen.web.throttle.msecs`; // need semi-colon (compiler bug?)
      if (msecs > 0) {
        val style = WebSettings.`gwen.web.highlight.style`
        executeJS(s"element = arguments[0]; type = element.getAttribute('type'); if (('radio' == type || 'checkbox' == type) && element.parentElement.getElementsByTagName('input').length == 1) { element = element.parentElement; } original_style = element.getAttribute('style'); element.setAttribute('style', original_style + '; $style'); setTimeout(function() { element.setAttribute('style', original_style); }, $msecs);", element)(WebSettings.`gwen.web.capture.screenshots.highlighting`)
        Thread.sleep(msecs)
      }
    }
  }

  /**
    * Checks the current state of an element.
    *
    * @param elementBinding the locator binding of the element
    * @param state the state to check
    * @param negate whether or not to negate the check
    */
  def checkElementState(elementBinding: LocatorBinding, state: String, negate: Boolean): Unit = {
    env.perform {
      var result = false
      try {
        withWebElement(elementBinding) { webElement =>
          result = state match {
            case "displayed" => webElement.isDisplayed
            case "hidden" => !webElement.isDisplayed
            case "checked" | "ticked" => webElement.isSelected
            case "unchecked" | "unticked" => !webElement.isSelected
            case "enabled" => webElement.isEnabled
            case "disabled" => !webElement.isEnabled
          }
        }
      } catch {
        case e: NoSuchElementException =>
          if ((state == "displayed" && negate) || (state == "hidden" && !negate)) result = !negate
          else if (state == "displayed" || state == "hidden") result = false
          else throw e
      }
      if (!negate) assert(result, s"${elementBinding.element} should be $state")
      else assert(!result, s"${elementBinding.element} should not be $state")
    }
    env.bindAndWait(elementBinding.element, state, "true")
  }

  /** Gets the title of the current page in the browser.*/
  def getTitle: String =
    withWebDriver { driver =>
      driver.getTitle tap { title =>
        env.bindAndWait("page", "title", title)
      }
    }.getOrElse("$[dryRun:title]")

  /**
    * Sends a value to a web element (one character at a time).
    *
    * @param elementBinding the web element locator binding
    * @param value the value to send
    * @param clearFirst true to clear field first (if element is a text field)
    * @param sendEnterKey true to send the Enter key after sending the value
    */
  def sendValue(elementBinding: LocatorBinding, value: String, clearFirst: Boolean, sendEnterKey: Boolean) {
    val element = elementBinding.element
    withWebElement(elementBinding) { webElement =>
      if (clearFirst) {
        webElement.clear()
        env.bindAndWait(element, "clear", "true")
      }
      webElement.sendKeys(value)
      env.bindAndWait(element, "type", value)
      if (sendEnterKey) {
        webElement.sendKeys(Keys.RETURN)
        env.bindAndWait(element, "enter", "true")
      }
    }
  }

  /**
    * Selects a value in a dropdown (select control) by visible text.
    *
    * @param elementBinding the web element locator binding
    * @param value the value to select
    */
  def selectByVisibleText(elementBinding: LocatorBinding, value: String) {
    withWebElement(elementBinding) { webElement =>
      logger.debug(s"Selecting '$value' in ${elementBinding.element} by text")
      new Select(webElement).selectByVisibleText(value)
      env.bindAndWait(elementBinding.element, "select", value)
    }
  }

  /**
    * Selects a value in a dropdown (select control) by value.
    *
    * @param elementBinding the web element locator binding
    * @param value the value to select
    */
  def selectByValue(elementBinding: LocatorBinding, value: String) {
    withWebElement(elementBinding) { webElement =>
      logger.debug(s"Selecting '$value' in ${elementBinding.element} by value")
      new Select(webElement).selectByValue(value)
      env.bindAndWait(elementBinding.element, "select", value)
    }
  }

  /**
    * Selects a value in a dropdown (select control) by index.
    *
    * @param elementBinding the web element locator binding
    * @param index the index to select (first index is 1)
    */
  def selectByIndex(elementBinding: LocatorBinding, index: Int) {
    withWebElement(elementBinding) { webElement =>
      logger.debug(s"Selecting option in ${elementBinding.element} by index: $index")
      val select = new Select(webElement)
      select.selectByIndex(index)
      env.bindAndWait(elementBinding.element, "select", select.getFirstSelectedOption.getText)
    }
  }

  def performAction(action: String, elementBinding: LocatorBinding) {
    val actionBinding = env.scopes.getOpt(s"${elementBinding.element}/action/$action/javascript")
    actionBinding match {
      case Some(javascript) =>
        performScriptAction(action, javascript, elementBinding)
      case None =>
        action match {
          case "click" =>
            withWebElement(action, elementBinding) { webElement =>
              executeJS("(function(element){element.focus();})(arguments[0]);", webElement)
              webElement.click()
            }
          case "right click" =>
            withDriverAndElement(action, elementBinding) { (driver, webElement) =>
              new Actions(driver).contextClick(webElement).perform()
            }
          case _ =>
            withWebElement(action, elementBinding) { webElement =>
              action match {
                case "submit" => webElement.submit()
                case "check" | "tick" =>
                  if (!webElement.isSelected) webElement.sendKeys(Keys.SPACE)
                  if (!webElement.isSelected) webElement.click()
                case "uncheck" | "untick" =>
                  if (webElement.isSelected) webElement.sendKeys(Keys.SPACE)
                  if (webElement.isSelected) webElement.click()
                case "clear" =>
                  webElement.clear()
              }
            }
        }
        env.bindAndWait(elementBinding.element, action, "true")
    }
  }

  def holdAndClick(modifierKeys: Array[String], clickAction: String, elementBinding: LocatorBinding) {
    val keys = modifierKeys.map(_.trim).map(key => Try(Keys.valueOf(key.toUpperCase)).getOrElse(unsupportedModifierKeyError(key)))
    withDriverAndElement(clickAction, elementBinding) { (driver, webElement) =>
      var actions = new Actions(driver)
      keys.foreach { key => actions = actions.keyDown(key) }
      actions = clickAction match {
        case "click" => actions.click(webElement)
        case "right click" => actions.contextClick(webElement)
      }
      keys.reverse.foreach { key => actions = actions.keyUp(key) }
      actions.build().perform()
    }
    env.bindAndWait(elementBinding.element, clickAction, "true")
  }

  def sendKeys(elementBinding: LocatorBinding, keysToSend: Array[String]): Unit = {
    val keys = keysToSend.map(_.trim).map(key => Try(Keys.valueOf(key.toUpperCase)).getOrElse(key))
    withDriverAndElement("send keys", elementBinding) { (driver, webElement) =>
      var actions = new Actions(driver)
      keys.foreach { key => actions = actions.sendKeys(webElement, key) }
      actions.build().perform()
    }
  }

  private def withDriverAndElement(desc: String, elementBinding: LocatorBinding)(doActions: (WebDriver, WebElement) => Unit): Unit = {
    withWebDriver { driver =>
      withWebElement(elementBinding) { webElement =>
        executeJS("(function(element){element.focus();})(arguments[0]);", webElement)
        doActions(driver, webElement)
      }
    }
  }

  private def performScriptAction(action: String, javascript: String, elementBinding: LocatorBinding) {
    withWebElement(action, elementBinding) { webElement =>
      executeJS(s"(function(element) { $javascript })(arguments[0])", webElement)
      env.bindAndWait(elementBinding.element, action, "true")
    }
  }

  /**
    * Performs and action on a web element in the context of another element.
    *
    * @param action description of the action
    * @param element the name of the element to perform the action on
    * @param context the name of the context element binding to find the element in
    */
  def performActionInContext(action: String, element: String, context: String): Unit = {
    try {
        val contextBinding = env.getLocatorBinding(context)
        val elementBinding = env.getLocatorBinding(element)
        performActionIn(action, elementBinding, contextBinding)
      } catch {
        case e1: LocatorBindingException =>
          try {
            val elementBinding = env.getLocatorBinding(s"$element of $context")
            performAction(action, elementBinding)
          } catch {
            case e2: LocatorBindingException =>
              throw new LocatorBindingException(s"'$element', '$context', or '$element of $context'", s"${e1.getMessage}, ${e2.getMessage}")
          }
      }
  }

  private def performActionIn(action: String, elementBinding: LocatorBinding, contextBinding: LocatorBinding) {
    def perform(webElement: WebElement, contextElement: WebElement)(buildAction: Actions => Actions) {
      withWebDriver { driver =>
        val moveTo = new Actions(driver).moveToElement(contextElement).moveToElement(webElement)
        buildAction(moveTo).build().perform()
      }
    }
    withWebElement(action, contextBinding) { contextElement =>
      withWebElement(action, elementBinding) { webElement =>
        action match {
          case "click" => perform(webElement, contextElement) { _.click() }
          case "right click" => perform(webElement, contextElement) { _.contextClick() }
          case "check" | "tick" =>
            if (!webElement.isSelected) perform(webElement, contextElement) { _.sendKeys(Keys.SPACE) }
            if (!webElement.isSelected) perform(webElement, contextElement) { _.click() }
          case "uncheck" | "untick" =>
            if (webElement.isSelected) perform(webElement, contextElement) { _.sendKeys(Keys.SPACE) }
            if (webElement.isSelected) perform(webElement, contextElement) { _.click() }
        }
        env.bindAndWait(elementBinding.element, action, "true")
      }
    }
  }

  /**
    * Waits for text to appear in the given web element.
    *
    * @param elementBinding the web element locator binding
    */
  def waitForText(elementBinding: LocatorBinding): Boolean =
    getElementText(elementBinding).map(_.length()).getOrElse {
      env.scopes.set(s"${elementBinding.element}/text", "text")
      0
    } > 0

  /**
   * Scrolls an element into view.
   *
   * @param elementBinding the web element locator binding
   * @param scrollTo scroll element into view, options are: top or bottom
   */
  def scrollIntoView(elementBinding: LocatorBinding, scrollTo: ScrollTo.Value) {
    withWebElement(elementBinding) { scrollIntoView(_, scrollTo) }
  }

  /**
   * Scrolls the given web element into view.
   *
   * @param webElement the web element to scroll to
   * @param scrollTo scroll element into view, options are: top or bottom
   */
  def scrollIntoView(webElement: WebElement, scrollTo: ScrollTo.Value) {
    executeJS(s"var elem = arguments[0]; if (typeof elem !== 'undefined' && elem != null) { elem.scrollIntoView(${scrollTo == ScrollTo.top}); }", webElement)
  }

  /**
    * Resizes the browser window to the given dimensions.
    *
    * @param width the width
    * @param height the height
    */
  def resizeWindow(width: Int, height: Int) {
    withWebDriver { driver =>
      logger.info(s"Resizing browser window to width $width and height $height")
      driver.manage().window().setSize(new Dimension(width, height))
    }
  }

  /**
    * Maximizes the browser window.
    */
  def maximizeWindow() {
    withWebDriver { driver =>
      logger.info("Maximising browser window")
      driver.manage().window().maximize()
    }
  }

  def captureCurrentUrl(asName: Option[String]): Unit = {
    val name = asName.getOrElse("the current URL")
    env.featureScope.set(name, withWebDriver { driver =>
      driver.getCurrentUrl tap { content =>
        env.addAttachment(name, "txt", content)
      }
    }.getOrElse("$[currentUrl]"))
  }

  /**
    * Gets the text value of a web element on the current page.
    * A search for the text is made in the following order and the first value
    * found is returned:
    *  - Web element text
    *  - Web element text attribute
    *  - Web element value attribute
    * If a value is found, its value is bound to the current page
    * scope as `name/text`.
    *
    * @param elementBinding the web element locator binding
    */
  def getElementText(elementBinding: LocatorBinding): Option[String] =
    withWebElement(elementBinding) { webElement =>
      (Option(webElement.getText) match {
        case None | Some("") =>
          Option(webElement.getAttribute("text")) match {
            case None | Some("") =>
              Option(webElement.getAttribute("value")) match {
                case None | Some("") =>
                  val value = executeJS("(function(element){return element.innerText || element.textContent || ''})(arguments[0]);", webElement).asInstanceOf[String]
                  if (value != null) value else ""
                case Some(value) => value
              }
            case Some(value) => value
          }
        case Some(value) => value
      }) tap { text =>
        env.bindAndWait(elementBinding.element, "text", text)
      }
    } tap { value =>
      logger.debug(s"getElementText(${elementBinding.element})='$value'")
    }

  /**
    * Gets the selected text of a dropdown web element on the current page.
    * If a value is found, its value is bound to the current page
    * scope as `name/selectedText`.
    *
    * @param name the web element name
    */
  private def getSelectedElementText(name: String): Option[String] = {
    val elementBinding = env.getLocatorBinding(name)
    withWebElement(elementBinding) { webElement =>
      val select = new Select(webElement)
      (Option(select.getAllSelectedOptions.asScala.map(_.getText()).mkString(",")) match {
        case None | Some("") =>
          select.getAllSelectedOptions.asScala.map(_.getAttribute("text")).mkString(",")
        case Some(value) => value
      }) tap { text =>
        env.bindAndWait(elementBinding.element, "selectedText", text)
      }
    } tap { value =>
      logger.debug(s"getSelectedElementText(${elementBinding.element})='$value'")
    }
  }

   /**
    * Gets the selected value of a dropdown web element on the current page.
    * If a value is found, its value is bound to the current page
    * scope as `name/selectedValue`.
    *
    * @param name the web element name
    */
  private def getSelectedElementValue(name: String): Option[String] = {
    val elementBinding = env.getLocatorBinding(name)
    withWebElement(elementBinding) { webElement =>
      new Select(webElement).getAllSelectedOptions.asScala.map(_.getAttribute("value")).mkString(",") tap { value =>
        env.bindAndWait(elementBinding.element, "selectedValue", value)
      }
    } tap { value =>
      logger.debug(s"getSelectedElementValue(${elementBinding.element})='$value'")
    }
  }

  /**
   * Gets an element's selected value(s).
   *
   * @param name the name of the element
   * @param selection `text` to get selected option text, `value` to get
   *        selected option value
   * @return the selected value or a comma seprated string containing all
   * the selected values if multiple values are selected.
   */
  def getElementSelection(name: String, selection: String): String = {
    selection.trim match {
      case "text" => getSelectedElementText(name)
      case _ => getSelectedElementValue(name)
    }
  }.getOrElse(s"$$[$name $selection]")

  /**
    * Switches the web driver session
    *
    * @param session the name of the session to switch to
    */
  def switchToSession(session: String): Unit = {
    env.perform {
      driverManager.switchToSession(session)
    }
  }

  /**
    * Switches to the child window if one was just opened.
    */
  def switchToChild(): Unit = {
    withWebDriver { driver =>
      driverManager.switchToChild(driver)
    }
  }

  /**
    * Closes the child window.
    */
  def closeChild(): Unit = {
    env.perform {
      driverManager.closeChild()
    }
  }

  /** Switches to the parent window. */
  def switchToParent(childClosed: Boolean): Unit = {
    env.perform {
      driverManager.switchToParent(childClosed)
    }
  }

  /** Refreshes the current page.*/
  def refreshPage(): Unit = {
    withWebDriver { driver =>
      driver.navigate().refresh()
    }
  }

  /**
    * Handles an alert (pop-up)
    *
    * @param accept true to accept; false to dismiss
    */
  def handleAlert(accept: Boolean): Unit = {
    withWebDriver { driver =>
      if (accept) {
        driver.switchTo().alert().accept()
      } else {
        driver.switchTo().alert().dismiss()
      }
    }
  }

  def navigateTo(url: String): Unit = {
    withWebDriver { driver =>
      driver.get(url)
    } (WebSettings.`gwen.web.capture.screenshots`)
  }

}
