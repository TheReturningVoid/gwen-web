/*
 * Copyright 2014-2017 Brady Wood, Branko Juric
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

import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.when
import org.openqa.selenium.By
import org.openqa.selenium.TimeoutException
import org.openqa.selenium.WebDriver
import org.openqa.selenium.WebElement
import org.openqa.selenium.firefox.FirefoxDriver
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.mockito.MockitoSugar
import gwen.eval.ScopedDataStack
import gwen.eval.GwenOptions
import gwen.web.errors.{LocatorBindingException, WaitTimeoutException}
import org.openqa.selenium.WebDriver.{Options, TargetLocator, Timeouts}
import org.openqa.selenium.NoSuchElementException

class WebElementLocatorTest extends FlatSpec with Matchers with MockitoSugar {

  val mockWebElement: WebElement = mock[WebElement]
  val mockWebElements: List[WebElement] = List(mock[WebElement], mock[WebElement])
  val mockContainerElement: WebElement = mock[WebElement]
  val mockIFrameElement: WebElement = mock[WebElement]
  val mockFrameElement: WebElement = mock[WebElement]
  val mockTargetLocator: TargetLocator = mock[TargetLocator]
  val mockWebDriverOptions: Options = mock[WebDriver.Options]
  val mockWebDriverTimeouts: Timeouts = mock[WebDriver.Timeouts]

  "Attempt to locate non existent element" should "throw no such element error" in {

    val mockWebDriver: FirefoxDriver = mock[FirefoxDriver]
    val locator = newLocator(None, mockWebDriver)
    
    when(mockWebDriver.manage()).thenReturn(mockWebDriverOptions)
    when(mockWebDriverOptions.timeouts()).thenReturn(mockWebDriverTimeouts)
    when(mockWebDriver.findElement(By.id("mname"))).thenReturn(null)
    
    val e = intercept[NoSuchElementException] {
      locator.locate(LocatorBinding("middleName", "id", "mname", None))
    }
    e.getMessage should startWith ("Could not locate middleName by (id: mname)")
  }
  
  "Attempt to locate existing element by id" should "return the element" in {
    shouldFindWebElement("id", "uname", By.id("uname"))
  }
  
  "Attempt to locate existing element by name" should "return the element" in {
    shouldFindWebElement("name", "uname", By.name("uname"))
  }
  
  "Attempt to locate existing element by tag name" should "return the element" in {
    shouldFindWebElement("tag name", "input", By.tagName("input"))
  }
  
  "Attempt to locate existing element by css selector" should "return the element" in {
    shouldFindWebElement("css selector", ":focus", By.cssSelector(":focus"))
  }
  
  "Attempt to locate existing element by xpath" should "return the element" in {
    shouldFindWebElement("xpath", "//input[name='uname']", By.xpath("//input[name='uname']"))
  }
  
  "Attempt to locate existing element by class name" should "return the element" in {
    shouldFindWebElement("class name", ".userinput", By.className(".userinput"))
  }
  
  "Attempt to locate existing element by link text" should "return the element" in {
    shouldFindWebElement("link text", "User name", By.linkText("User name"))
  }
  
  "Attempt to locate existing element by partial link text" should "return the element" in {
    shouldFindWebElement("partial link text", "User", By.partialLinkText("User"))
  }
  
  "Attempt to locate existing element by javascript" should "return the element" in {
    
    val locatorType = "javascript"
    val lookup = "document.getElementById('username')"
    val mockWebDriver: FirefoxDriver = mock[FirefoxDriver]
    val locator = newLocator(None, mockWebDriver)
    
    when(mockWebDriver.manage()).thenReturn(mockWebDriverOptions)
    when(mockWebDriverOptions.timeouts()).thenReturn(mockWebDriverTimeouts)
    doReturn(mockWebElement).when(mockWebDriver).executeScript(s"return $lookup")
    when(mockWebElement.isDisplayed).thenReturn(true)
    
    locator.locate(LocatorBinding("username", locatorType, lookup, None)) should be (mockWebElement)
    
    verify(mockWebDriver, times(1)).executeScript(s"return $lookup")

  }
  
  "Timeout on locating element by javascript" should "throw error" in {
    
    val locatorType = "javascript"
    val lookup = "document.getElementById('username')"
    val mockWebDriver: FirefoxDriver = mock[FirefoxDriver]
    val locator = newLocator(None, mockWebDriver)
    
    val timeoutError = new TimeoutException()
    when(mockWebDriver.manage()).thenReturn(mockWebDriverOptions)
    when(mockWebDriverOptions.timeouts()).thenReturn(mockWebDriverTimeouts)
    doThrow(timeoutError).when(mockWebDriver).executeScript(s"return $lookup")
    
    intercept[WaitTimeoutException] {
      locator.locate(LocatorBinding("username", locatorType, lookup, None))
    }
    
    verify(mockWebDriver, atLeastOnce()).executeScript(s"return $lookup")

  }
  
  private def shouldFindWebElement(locatorType: String, lookup: String, by: By) {

    val env = newEnv
    val mockWebDriver: FirefoxDriver = mock[FirefoxDriver]
    val locator = newLocator(Some(env), mockWebDriver)
    
    when(mockWebDriver.manage()).thenReturn(mockWebDriverOptions)
    when(mockWebDriverOptions.timeouts()).thenReturn(mockWebDriverTimeouts)
    when(mockWebDriver.findElement(by)).thenReturn(mockWebElement)
    when(mockWebElement.isDisplayed).thenReturn(true)
    
    when(mockWebDriver.findElement(By.id("container"))).thenReturn(mockContainerElement)
    when(mockContainerElement.getTagName).thenReturn("div")
    when(mockContainerElement.findElement(by)).thenReturn(mockWebElement)
    env.scopes.set("container/locator", "id")
    env.scopes.set("container/locator/id", "container")
    
    when(mockWebDriver.findElement(By.id("iframe"))).thenReturn(mockIFrameElement)
    when(mockIFrameElement.getTagName).thenReturn("iframe")
    when(mockWebDriver.switchTo()).thenReturn(mockTargetLocator)
    when(mockTargetLocator.frame(mockIFrameElement)).thenReturn(mockWebDriver)
    env.scopes.set("iframe/locator", "id")
    env.scopes.set("iframe/locator/id", "iframe")
    
    when(mockWebDriver.findElement(By.id("frame"))).thenReturn(mockFrameElement)
    when(mockFrameElement.getTagName).thenReturn("frame")
    when(mockWebDriver.switchTo()).thenReturn(mockTargetLocator)
    when(mockTargetLocator.frame(mockFrameElement)).thenReturn(mockWebDriver)
    env.scopes.set("frame/locator", "id")
    env.scopes.set("frame/locator/id", "frame")

    locator.locate(LocatorBinding("username", locatorType, lookup, None)) should be (mockWebElement)
    locator.locate(LocatorBinding("username", locatorType, lookup, Some("container"))) should be (mockWebElement)
    locator.locate(LocatorBinding("username", locatorType, lookup, Some("iframe"))) should be (mockWebElement)
    locator.locate(LocatorBinding("username", locatorType, lookup, Some("frame"))) should be (mockWebElement)
    
    verify(mockWebDriver, times(3)).findElement(by)
    
  }
  
  "Attempt to locate element with unsupported locator" should "throw unsupported locator error" in {

    val env = newEnv
    val mockWebDriver: FirefoxDriver = mock[FirefoxDriver]
    val locator = newLocator(Some(env), mockWebDriver)

    env.scopes.addScope("login").set("username/id", "unknown").set("username/id/unknown", "funkyness")
    val e = intercept[LocatorBindingException] {
      locator.locate(LocatorBinding("username", "unknown", "funkiness", None))
    }
    e.getMessage should be ("Could not locate username: unsupported locator: (unknown: funkiness)")
  }

  "Attempt to locate all non existent elements" should "return an empty list when empty array is returned" in {

    val mockWebDriver: FirefoxDriver = mock[FirefoxDriver]
    val locator = newLocator(None, mockWebDriver)

    when(mockWebDriver.manage()).thenReturn(mockWebDriverOptions)
    when(mockWebDriverOptions.timeouts()).thenReturn(mockWebDriverTimeouts)
    when(mockWebDriver.findElements(By.cssSelector(".mname"))).thenReturn(new java.util.ArrayList[WebElement]())

    locator.locateAll(LocatorBinding("middleNames", "css selector", ".mname", None)) should be (Nil)
  }

  "Attempt to locate all non existent elements" should "return an empty list when null is returned" in {

    val mockWebDriver: FirefoxDriver = mock[FirefoxDriver]
    val locator = newLocator(None, mockWebDriver)

    when(mockWebDriver.manage()).thenReturn(mockWebDriverOptions)
    when(mockWebDriverOptions.timeouts()).thenReturn(mockWebDriverTimeouts)
    when(mockWebDriver.findElements(By.cssSelector(".mname"))).thenReturn(null)

    locator.locateAll(LocatorBinding("middleNames", "css selector", ".mname", None)) should be (Nil)
  }

  "Attempt to locate existing elements by id" should "return the elements" in {
    shouldFindAllWebElements("id", "uname", By.id("uname"))
  }

  "Attempt to locate existing elements by name" should "return the elements" in {
    shouldFindAllWebElements("name", "uname", By.name("uname"))
  }

  "Attempt to locate existing elements by tag name" should "return the elements" in {
    shouldFindAllWebElements("tag name", "input", By.tagName("input"))
  }

  "Attempt to locate existing elements by css selector" should "return the elements" in {
    shouldFindAllWebElements("css selector", ":focus", By.cssSelector(":focus"))
  }

  "Attempt to locate existing elements by xpath" should "return the elements" in {
    shouldFindAllWebElements("xpath", "//input[name='uname']", By.xpath("//input[name='uname']"))
  }

  "Attempt to locate existing elements by class name" should "return the elements" in {
    shouldFindAllWebElements("class name", ".userinput", By.className(".userinput"))
  }

  "Attempt to locate existing elements by link text" should "return the elements" in {
    shouldFindAllWebElements("link text", "User name", By.linkText("User name"))
  }

  "Attempt to locate existing elements by partial link text" should "return the elements" in {
    shouldFindAllWebElements("partial link text", "User", By.partialLinkText("User"))
  }

  "Attempt to locate existing elements by javascript" should "return the elements" in {

    val locatorType = "javascript"
    val lookup = "document.getElementsByName('username')"
    val mockWebDriver: FirefoxDriver = mock[FirefoxDriver]
    val locator = newLocator(None, mockWebDriver)

    val mockWebElementsArrayList = new java.util.ArrayList[WebElement]()
    mockWebElementsArrayList.add(mockWebElements(0))
    mockWebElementsArrayList.add(mockWebElements(1))

    when(mockWebDriver.manage()).thenReturn(mockWebDriverOptions)
    when(mockWebDriverOptions.timeouts()).thenReturn(mockWebDriverTimeouts)
    doReturn(mockWebElementsArrayList).when(mockWebDriver).executeScript(s"return $lookup")
    when(mockWebElement.isDisplayed).thenReturn(true)

    locator.locateAll(LocatorBinding("username", locatorType, lookup, None)) should be (mockWebElements)

    verify(mockWebDriver, times(1)).executeScript(s"return $lookup")

  }

  "Timeout on locating elements by javascript" should "throw error" in {

    val locatorType = "javascript"
    val lookup = "document.getElementById('username')"
    val mockWebDriver: FirefoxDriver = mock[FirefoxDriver]
    val locator = newLocator(None, mockWebDriver)

    val timeoutError = new TimeoutException()
    when(mockWebDriver.manage()).thenReturn(mockWebDriverOptions)
    when(mockWebDriverOptions.timeouts()).thenReturn(mockWebDriverTimeouts)
    doThrow(timeoutError).when(mockWebDriver).executeScript(s"return $lookup")

    intercept[WaitTimeoutException] {
      locator.locate(LocatorBinding("username", locatorType, lookup, None))
    }

    verify(mockWebDriver, atLeastOnce()).executeScript(s"return $lookup")

  }

  private def shouldFindAllWebElements(locatorType: String, lookup: String, by: By) {

    val env = newEnv
    val mockWebDriver: FirefoxDriver = mock[FirefoxDriver]
    val locator = newLocator(Some(env), mockWebDriver)

    val mockWebElementsJava = new java.util.ArrayList[WebElement]()
    mockWebElementsJava.add(mockWebElements(0))
    mockWebElementsJava.add(mockWebElements(1))

    when(mockWebDriver.manage()).thenReturn(mockWebDriverOptions)
    when(mockWebDriverOptions.timeouts()).thenReturn(mockWebDriverTimeouts)
    when(mockWebDriver.findElements(by)).thenReturn(mockWebElementsJava)
    when(mockWebElement.isDisplayed).thenReturn(true)

    when(mockWebDriver.findElement(By.id("container"))).thenReturn(mockContainerElement)
    when(mockContainerElement.getTagName).thenReturn("div")
    when(mockContainerElement.findElements(by)).thenReturn(mockWebElementsJava)
    env.scopes.set("container/locator", "id")
    env.scopes.set("container/locator/id", "container")

    when(mockWebDriver.findElement(By.id("iframe"))).thenReturn(mockIFrameElement)
    when(mockIFrameElement.getTagName).thenReturn("iframe")
    when(mockWebDriver.switchTo()).thenReturn(mockTargetLocator)
    when(mockTargetLocator.frame(mockIFrameElement)).thenReturn(mockWebDriver)
    env.scopes.set("iframe/locator", "id")
    env.scopes.set("iframe/locator/id", "iframe")

    when(mockWebDriver.findElement(By.id("frame"))).thenReturn(mockFrameElement)
    when(mockFrameElement.getTagName).thenReturn("frame")
    when(mockWebDriver.switchTo()).thenReturn(mockTargetLocator)
    when(mockTargetLocator.frame(mockFrameElement)).thenReturn(mockWebDriver)
    env.scopes.set("frame/locator", "id")
    env.scopes.set("frame/locator/id", "frame")

    locator.locateAll(LocatorBinding("username", locatorType, lookup, None)) should be (mockWebElements)
    locator.locateAll(LocatorBinding("username", locatorType, lookup, Some("container"))) should be (mockWebElements)
    locator.locateAll(LocatorBinding("username", locatorType, lookup, Some("iframe"))) should be (mockWebElements)
    locator.locateAll(LocatorBinding("username", locatorType, lookup, Some("frame"))) should be (mockWebElements)

    verify(mockWebDriver, times(3)).findElements(by)

  }

  "Attempt to locate existing element by three locators" should "return the element by first one" in {

    val locators = List(
      Locator("id", "uname"),
      Locator("name", "username"),
      Locator("class name", ".usrname")
    )
    val binding = LocatorBinding("username", locators)

    val env = newEnv
    val mockWebDriver: FirefoxDriver = mock[FirefoxDriver]
    val locator = newLocator(Some(env), mockWebDriver)

    when(mockWebDriver.manage()).thenReturn(mockWebDriverOptions)
    when(mockWebDriverOptions.timeouts()).thenReturn(mockWebDriverTimeouts)
    when(mockWebDriver.findElement(By.id("uname"))).thenReturn(mockWebElement)
    when(mockWebElement.isDisplayed).thenReturn(true)

    locator.locate(binding) should be (mockWebElement)

    verify(mockWebDriver, times(1)).findElement(By.id("uname"))
    verify(mockWebDriver, times(0)).findElement(By.name("username"))
    verify(mockWebDriver, times(0)).findElement(By.className(".usrname"))

  }

  "Attempt to locate existing element by three locators" should "return the element by second one" in {

    val locators = List(
      Locator("id", "uname"),
      Locator("name", "username"),
      Locator("class name", ".usrname")
    )
    val binding = LocatorBinding("username", locators)

    val env = newEnv
    val mockWebDriver: FirefoxDriver = mock[FirefoxDriver]
    val locator = newLocator(Some(env), mockWebDriver)

    when(mockWebDriver.manage()).thenReturn(mockWebDriverOptions)
    when(mockWebDriverOptions.timeouts()).thenReturn(mockWebDriverTimeouts)
    when(mockWebDriver.findElement(By.id("uname"))).thenReturn(null)
    when(mockWebDriver.findElement(By.name("username"))).thenReturn(mockWebElement)
    when(mockWebElement.isDisplayed).thenReturn(true)

    locator.locate(binding) should be (mockWebElement)

    verify(mockWebDriver, times(1)).findElement(By.id("uname"))
    verify(mockWebDriver, times(1)).findElement(By.name("username"))
    verify(mockWebDriver, times(0)).findElement(By.className(".usrname"))

  }

  "Attempt to locate existing element by three locators" should "return the element by third one" in {

    val locators = List(
      Locator("id", "uname"),
      Locator("name", "username"),
      Locator("class name", ".usrname")
    )
    val binding = LocatorBinding("username", locators)

    val env = newEnv
    val mockWebDriver: FirefoxDriver = mock[FirefoxDriver]
    val locator = newLocator(Some(env), mockWebDriver)

    when(mockWebDriver.manage()).thenReturn(mockWebDriverOptions)
    when(mockWebDriverOptions.timeouts()).thenReturn(mockWebDriverTimeouts)
    when(mockWebDriver.findElement(By.id("uname"))).thenReturn(null)
    when(mockWebDriver.findElement(By.name("username"))).thenReturn(null)
    when(mockWebDriver.findElement(By.className(".usrname"))).thenReturn(mockWebElement)
    when(mockWebElement.isDisplayed).thenReturn(true)

    locator.locate(binding) should be (mockWebElement)

    verify(mockWebDriver, times(1)).findElement(By.id("uname"))
    verify(mockWebDriver, times(1)).findElement(By.name("username"))
    verify(mockWebDriver, times(1)).findElement(By.className(".usrname"))

  }
  
  private def newLocator(env: Option[WebEnvContext], webDriver: WebDriver): WebElementLocator =
    new WebContext(env.getOrElse(newEnv)) {
     override def withWebDriver[T](function: WebDriver => T)(implicit takeScreenShot: Boolean = false): Option[T] =
       Option(function(webDriver))
  }

  private def newEnv: WebEnvContext = new WebEnvContext(GwenOptions(), new ScopedDataStack())
  
}