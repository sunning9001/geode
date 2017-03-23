/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.management.internal.security;

import static org.apache.geode.distributed.ConfigurationProperties.SECURITY_MANAGER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.apache.geode.cache.RegionShortcut;
import org.apache.geode.internal.logging.LogService;
import org.apache.geode.management.cli.Result;
import org.apache.geode.management.internal.cli.result.CommandResult;
import org.apache.geode.management.internal.cli.result.ErrorResultData;
import org.apache.geode.management.internal.cli.result.ResultBuilder;
import org.apache.geode.security.TestSecurityManager;
import org.apache.geode.test.dunit.rules.ConnectionConfiguration;
import org.apache.geode.test.dunit.rules.GfshShellConnectionRule;
import org.apache.geode.test.dunit.rules.ServerStarterRule;
import org.apache.geode.test.junit.categories.IntegrationTest;
import org.apache.geode.test.junit.categories.SecurityTest;
import org.apache.shiro.authz.permission.WildcardPermission;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.util.List;

@Category({IntegrationTest.class, SecurityTest.class})
public class GfshCommandsSecurityTest {
  @ClassRule
  public static ServerStarterRule serverStarter = new ServerStarterRule().withJMXManager()
      .withProperty(SECURITY_MANAGER, TestSecurityManager.class.getName())
      .withProperty("security-json",
          "org/apache/geode/management/internal/security/cacheServer.json")
      .withAutoStart();

  @Rule
  public GfshShellConnectionRule gfshConnection = new GfshShellConnectionRule(
      serverStarter::getJmxPort, GfshShellConnectionRule.PortType.jmxManger);

  @BeforeClass
  public static void beforeClass() throws Exception {
    serverStarter.getCache().createRegionFactory(RegionShortcut.REPLICATE).create("region1");
  }

  @Test
  @ConnectionConfiguration(user = "data-admin", password = "wrongPwd")
  public void testInvalidCredentials() throws Exception {
    assertFalse(gfshConnection.isConnected());
  }

  @Test
  @ConnectionConfiguration(user = "data-admin", password = "1234567")
  public void testValidCredentials() throws Exception {
    assertTrue(gfshConnection.isConnected());
  }

  @Test
  @ConnectionConfiguration(user = "cluster-reader", password = "1234567")
  public void testClusterReader() throws Exception {
    runCommandsWithAndWithout("CLUSTER:READ");
  }

  @Test
  @ConnectionConfiguration(user = "cluster-writer", password = "1234567")
  public void testClusterWriter() throws Exception {
    runCommandsWithAndWithout("CLUSTER:WRITE");
  }

  @Test
  @ConnectionConfiguration(user = "cluster-manager", password = "1234567")
  public void testClusterManager() throws Exception {
    runCommandsWithAndWithout("CLUSTER:MANAGE");
  }

  @Test
  @ConnectionConfiguration(user = "data-reader", password = "1234567")
  public void testDataReader() throws Exception {
    runCommandsWithAndWithout("DATA:READ");
  }

  @Test
  @ConnectionConfiguration(user = "data-writer", password = "1234567")
  public void testDataWriter() throws Exception {
    runCommandsWithAndWithout("DATA:WRITE");
  }

  @Test
  @ConnectionConfiguration(user = "data-manager", password = "1234567")
  public void testDataManager() throws Exception {
    runCommandsWithAndWithout("DATA:MANAGE");
  }

  @Test
  @ConnectionConfiguration(user = "regionA-reader", password = "1234567")
  public void testRegionAReader() throws Exception {
    runCommandsWithAndWithout("DATA:READ:RegionA");
  }

  @Test
  @ConnectionConfiguration(user = "regionA-writer", password = "1234567")
  public void testRegionAWriter() throws Exception {
    runCommandsWithAndWithout("DATA:WRITE:RegionA");
  }

  @Test
  @ConnectionConfiguration(user = "regionA-manager", password = "1234567")
  public void testRegionAManager() throws Exception {
    runCommandsWithAndWithout("DATA:MANAGE:RegionA");
  }

  private void runCommandsWithAndWithout(String permission) throws Exception {
    List<TestCommand> allPermitted =
        TestCommand.getPermittedCommands(new WildcardPermission(permission, true));
    for (TestCommand permitted : allPermitted) {
      System.out.println("Processing authorized command: " + permitted.getCommand());
      CommandResult result = gfshConnection.executeCommand(permitted.getCommand());
      assertNotNull(result);

      if (result.getResultData() instanceof ErrorResultData) {
        assertNotEquals(ResultBuilder.ERRORCODE_UNAUTHORIZED,
            ((ErrorResultData) result.getResultData()).getErrorCode());
      } else {
        assertEquals(Result.Status.OK, result.getStatus());
      }
    }

    List<TestCommand> others = TestCommand.getCommands();
    others.removeAll(allPermitted);
    for (TestCommand other : others) {
      // skip no permission commands
      if (other.getPermission() == null)
        continue;

      System.out.println("Processing unauthorized command: " + other.getCommand());
      CommandResult result = (CommandResult) gfshConnection.executeCommand(other.getCommand());
      int errorCode = ((ErrorResultData) result.getResultData()).getErrorCode();

      // for some commands there are pre execution checks to check for user input error, will skip
      // those commands
      if (errorCode == ResultBuilder.ERRORCODE_USER_ERROR) {
        LogService.getLogger().info("Skip user error: " + result.getContent());
        continue;
      }

      assertEquals(ResultBuilder.ERRORCODE_UNAUTHORIZED,
          ((ErrorResultData) result.getResultData()).getErrorCode());
      String resultMessage = result.getContent().toString();
      String permString = other.getPermission().toString();
      assertTrue(resultMessage + " does not contain " + permString,
          resultMessage.contains(permString));
    }
  }

  @Test
  @ConnectionConfiguration(user = "data-user", password = "1234567")
  public void testGetPostProcess() throws Exception {
    gfshConnection.executeCommand("put --region=region1 --key=key2 --value=value2");
    gfshConnection.executeCommand("put --region=region1 --key=key2 --value=value2");
    gfshConnection.executeCommand("put --region=region1 --key=key3 --value=value3");

    // gfsh.executeCommand("get --region=region1 --key=key1");
    gfshConnection.executeCommand("query --query=\"select * from /region1\"");
  }

}
