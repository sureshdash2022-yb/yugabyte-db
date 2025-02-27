// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.controllers;

import static com.yugabyte.yw.common.AssertHelper.assertAuditEntry;
import static com.yugabyte.yw.common.AssertHelper.assertOk;
import static com.yugabyte.yw.common.AssertHelper.assertValue;
import static com.yugabyte.yw.common.AssertHelper.assertPlatformException;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static play.mvc.Http.Status.BAD_REQUEST;
import static play.mvc.Http.Status.OK;
import static play.test.Helpers.contentAsString;
import static play.test.Helpers.contextComponents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;
import com.yugabyte.yw.commissioner.Commissioner;
import com.yugabyte.yw.common.audit.AuditService;
import com.yugabyte.yw.common.FakeApiHelper;
import com.yugabyte.yw.common.FakeDBApplication;
import com.yugabyte.yw.common.ModelFactory;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.forms.CreatePitrConfigParams;
import com.yugabyte.yw.forms.RestoreSnapshotScheduleParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.PitrConfig;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.Users;
import com.yugabyte.yw.models.extended.UserWithFeatures;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import junitparams.JUnitParamsRunner;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yb.client.DeleteSnapshotScheduleResponse;
import org.yb.client.ListSnapshotSchedulesResponse;
import org.yb.client.SnapshotScheduleInfo;
import org.yb.client.SnapshotInfo;
import org.yb.client.YBClient;
import org.yb.CommonTypes.TableType;
import org.yb.master.CatalogEntityInfo.SysSnapshotEntryPB.State;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;

@RunWith(JUnitParamsRunner.class)
public class PitrControllerTest extends FakeDBApplication {

  public static final Logger LOG = LoggerFactory.getLogger(PitrControllerTest.class);

  private Universe defaultUniverse;
  private Users defaultUser;
  private Customer defaultCustomer;
  private UUID taskUUID;
  private YBClient mockClient;
  private AuditService auditService;
  private PitrController pitrController;
  private ListSnapshotSchedulesResponse mockListSnapshotSchedulesResponse;
  private DeleteSnapshotScheduleResponse mockDeleteSnapshotScheduleResponse;

  @Before
  public void setUp() {
    mockClient = mock(YBClient.class);
    mockListSnapshotSchedulesResponse = mock(ListSnapshotSchedulesResponse.class);
    mockDeleteSnapshotScheduleResponse = mock(DeleteSnapshotScheduleResponse.class);
    when(mockService.getClient(any(), any())).thenReturn(mockClient);
    defaultCustomer = ModelFactory.testCustomer();
    defaultUser = ModelFactory.testUser(defaultCustomer);
    defaultUniverse = ModelFactory.createUniverse(defaultCustomer.getCustomerId());
    UniverseDefinitionTaskParams details = defaultUniverse.getUniverseDetails();
    details.getPrimaryCluster().userIntent.ybSoftwareVersion = "2.14.0.0-b111";
    defaultUniverse.setUniverseDetails(details);
    defaultUniverse.save();
    Commissioner commissioner = app.injector().instanceOf(Commissioner.class);
    auditService = new AuditService();
    pitrController = new PitrController(commissioner, mockService);
    pitrController.setAuditService(auditService);
  }

  private Result createPitrConfig(
      UUID universeUUID, String dbEndpoint, String keyspaceName, ObjectNode bodyJson) {
    String authToken = defaultUser.createAuthToken();
    String method = "POST";
    String url =
        "/api/customers/"
            + defaultCustomer.uuid
            + "/universes/"
            + universeUUID.toString()
            + "/keyspaces/"
            + dbEndpoint
            + "/"
            + keyspaceName
            + "/pitr_config";
    return FakeApiHelper.doRequestWithAuthTokenAndBody(method, url, authToken, bodyJson);
  }

  private Result performPitr(UUID universeUUID, JsonNode bodyJson) {
    String authToken = defaultUser.createAuthToken();
    String method = "POST";
    String url =
        "/api/customers/"
            + defaultCustomer.uuid
            + "/universes/"
            + universeUUID.toString()
            + "/pitr";
    return FakeApiHelper.doRequestWithAuthTokenAndBody(method, url, authToken, bodyJson);
  }

  @Test
  public void testCreatePitrConfig() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    ObjectNode bodyJson = Json.newObject();
    bodyJson.put("retentionPeriodInSeconds", 7 * 86400L);
    bodyJson.put("intervalInSeconds", 86400L);
    Result r = createPitrConfig(defaultUniverse.universeUUID, "YSQL", "yugabyte", bodyJson);
    assertOk(r);
    JsonNode resultJson = Json.parse(contentAsString(r));
    assertValue(resultJson, "taskUUID", fakeTaskUUID.toString());
    assertEquals(OK, r.status());
    verify(mockCommissioner, times(1)).submit(any(), any());
    assertAuditEntry(1, defaultCustomer.uuid);
  }

  @Test
  public void testCreatePitrConfigWithIncompatibleUniverse() {
    UUID fakeTaskUUID = UUID.randomUUID();
    UniverseDefinitionTaskParams details = defaultUniverse.getUniverseDetails();
    details.getPrimaryCluster().userIntent.ybSoftwareVersion = "2.12.0.0-b111";
    defaultUniverse.setUniverseDetails(details);
    defaultUniverse.save();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    ObjectNode bodyJson = Json.newObject();
    bodyJson.put("retentionPeriodInSeconds", 7 * 86400L);
    bodyJson.put("intervalInSeconds", 86400L);
    Result r =
        assertPlatformException(
            () -> createPitrConfig(defaultUniverse.universeUUID, "YSQL", "yugabyte", bodyJson));
    JsonNode resultJson = Json.parse(contentAsString(r));
    assertEquals(BAD_REQUEST, r.status());
    verify(mockCommissioner, times(0)).submit(any(), any());
  }

  @Test
  public void testCreatePitrConfigWithoutUniverse() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    ObjectNode bodyJson = Json.newObject();
    bodyJson.put("retentionPeriodInSeconds", 7 * 86400L);
    bodyJson.put("intervalInSeconds", 86400L);
    Result r =
        assertPlatformException(
            () -> createPitrConfig(UUID.randomUUID(), "YSQL", "yugabyte", bodyJson));
    JsonNode resultJson = Json.parse(contentAsString(r));
    assertEquals(BAD_REQUEST, r.status());
    verify(mockCommissioner, times(0)).submit(any(), any());
  }

  @Test
  public void testCreatePitrConfigWithUniversePaused() {
    UUID fakeTaskUUID = UUID.randomUUID();
    UniverseDefinitionTaskParams details = defaultUniverse.getUniverseDetails();
    details.universePaused = true;
    defaultUniverse.setUniverseDetails(details);
    defaultUniverse.save();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    ObjectNode bodyJson = Json.newObject();
    bodyJson.put("retentionPeriodInSeconds", 7 * 86400L);
    bodyJson.put("intervalInSeconds", 86400L);
    Result r =
        assertPlatformException(
            () -> createPitrConfig(defaultUniverse.universeUUID, "YSQL", "yugabyte", bodyJson));
    JsonNode resultJson = Json.parse(contentAsString(r));
    assertEquals(BAD_REQUEST, r.status());
    verify(mockCommissioner, times(0)).submit(any(), any());
  }

  @Test
  public void testCreatePitrConfigWithNegativeRetentionPeriod() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    ObjectNode bodyJson = Json.newObject();
    bodyJson.put("retentionPeriodInSeconds", -7 * 86400L);
    bodyJson.put("intervalInSeconds", 86400L);
    Result r =
        assertPlatformException(
            () -> createPitrConfig(defaultUniverse.universeUUID, "YSQL", "yugabyte", bodyJson));
    JsonNode resultJson = Json.parse(contentAsString(r));
    assertValue(resultJson, "error", "PITR Config retention period cannot be less than 1 second");
    assertEquals(BAD_REQUEST, r.status());
    verify(mockCommissioner, times(0)).submit(any(), any());
  }

  @Test
  public void testCreatePitrConfigWithIntervalGreaterThanRetention() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    ObjectNode bodyJson = Json.newObject();
    bodyJson.put("retentionPeriodInSeconds", 43200L);
    bodyJson.put("intervalInSeconds", 86400L);
    Result r =
        assertPlatformException(
            () -> createPitrConfig(defaultUniverse.universeUUID, "YSQL", "yugabyte", bodyJson));
    JsonNode resultJson = Json.parse(contentAsString(r));
    assertValue(resultJson, "error", "PITR Config interval cannot be less than retention period");
    assertEquals(BAD_REQUEST, r.status());
    verify(mockCommissioner, times(0)).submit(any(), any());
  }

  @Test
  public void testCreatePitrConfigWithIncorrectTableType() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    ObjectNode bodyJson = Json.newObject();
    bodyJson.put("retentionPeriodInSeconds", 7 * 86400L);
    bodyJson.put("intervalInSeconds", 86400L);
    assertThrows(
        IllegalArgumentException.class,
        () -> createPitrConfig(defaultUniverse.universeUUID, "YQL", "yugabyte", bodyJson));
  }

  @Test
  public void testCreatePitrConfigWithPitrConfigAlreadyPresent() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    ObjectNode bodyJson = Json.newObject();
    bodyJson.put("retentionPeriodInSeconds", 7 * 86400L);
    bodyJson.put("intervalInSeconds", 86400L);
    CreatePitrConfigParams params = new CreatePitrConfigParams();
    params.retentionPeriodInSeconds = 7 * 86400L;
    params.intervalInSeconds = 86400L;
    params.universeUUID = defaultUniverse.universeUUID;
    params.customerUUID = defaultCustomer.uuid;
    params.keyspaceName = "yugabyte";
    params.tableType = TableType.PGSQL_TABLE_TYPE;
    PitrConfig.create(UUID.randomUUID(), params);
    Result r =
        assertPlatformException(
            () -> createPitrConfig(defaultUniverse.universeUUID, "YSQL", "yugabyte", bodyJson));
    JsonNode resultJson = Json.parse(contentAsString(r));
    assertValue(resultJson, "error", "PITR Config is already present");
    assertEquals(BAD_REQUEST, r.status());
    verify(mockCommissioner, times(0)).submit(any(), any());
  }

  private Result listPitrConfigs(
      UUID universeUUID, String dbEndpoint, String keyspaceName, ObjectNode bodyJson) {
    String authToken = defaultUser.createAuthToken();
    String method = "GET";
    String url =
        "/api/customers/"
            + defaultCustomer.uuid
            + "/universes/"
            + universeUUID.toString()
            + "/pitr_config";
    return FakeApiHelper.doRequestWithAuthTokenAndBody(method, url, authToken, bodyJson);
  }

  @Test
  public void testListPitrConfigs() throws Exception {
    List<SnapshotScheduleInfo> scheduleInfoList = new ArrayList<>();
    Map<UUID, SnapshotScheduleInfo> scheduleInfoMap = new HashMap<>();

    UUID scheduleUUID1 = UUID.randomUUID();
    CreatePitrConfigParams params1 = new CreatePitrConfigParams();
    params1.retentionPeriodInSeconds = 7 * 86400L;
    params1.intervalInSeconds = 86400L;
    params1.universeUUID = defaultUniverse.universeUUID;
    params1.customerUUID = defaultCustomer.uuid;
    params1.keyspaceName = "yugabyte";
    params1.tableType = TableType.PGSQL_TABLE_TYPE;
    PitrConfig pitr1 = PitrConfig.create(scheduleUUID1, params1);
    List<SnapshotInfo> snapshotList1 = new ArrayList<>();
    UUID snapshotUUID11 = UUID.randomUUID();
    long snapshotTime11 = System.currentTimeMillis() + 2 * 1000 * 86400L;
    SnapshotInfo snapshot11 =
        new SnapshotInfo(
            snapshotUUID11, snapshotTime11, snapshotTime11 - 1000 * 86400L, State.COMPLETE);
    snapshotList1.add(snapshot11);
    UUID snapshotUUID12 = UUID.randomUUID();
    long snapshotTime12 = System.currentTimeMillis() + 1000 * 86400L;
    SnapshotInfo snapshot12 =
        new SnapshotInfo(
            snapshotUUID12, snapshotTime12, snapshotTime12 - 1000 * 86400L, State.COMPLETE);
    snapshotList1.add(snapshot12);
    SnapshotScheduleInfo schedule1 =
        new SnapshotScheduleInfo(scheduleUUID1, 86400L, 7L * 86400L, snapshotList1);
    scheduleInfoList.add(schedule1);
    scheduleInfoMap.put(scheduleUUID1, schedule1);

    UUID scheduleUUID2 = UUID.randomUUID();
    CreatePitrConfigParams params2 = new CreatePitrConfigParams();
    params2.retentionPeriodInSeconds = 7 * 86400L;
    params2.intervalInSeconds = 86400L;
    params2.universeUUID = defaultUniverse.universeUUID;
    params2.customerUUID = defaultCustomer.uuid;
    params2.keyspaceName = "postgres";
    params2.tableType = TableType.PGSQL_TABLE_TYPE;
    PitrConfig pitr2 = PitrConfig.create(scheduleUUID2, params2);
    List<SnapshotInfo> snapshotList2 = new ArrayList<>();
    UUID snapshotUUID21 = UUID.randomUUID();
    long snapshotTime21 = System.currentTimeMillis() + 2 * 1000 * 86400L;
    SnapshotInfo snapshot21 =
        new SnapshotInfo(
            snapshotUUID21, snapshotTime21, snapshotTime21 - 1000 * 86400L, State.FAILED);
    snapshotList2.add(snapshot21);
    UUID snapshotUUID22 = UUID.randomUUID();
    long snapshotTime22 = System.currentTimeMillis() + 1000 * 86400L;
    SnapshotInfo snapshot22 =
        new SnapshotInfo(
            snapshotUUID22, snapshotTime22, snapshotTime22 - 1000 * 86400L, State.COMPLETE);
    snapshotList2.add(snapshot22);
    SnapshotScheduleInfo schedule2 =
        new SnapshotScheduleInfo(scheduleUUID2, 86400L, 7L * 86400L, snapshotList2);
    scheduleInfoList.add(schedule2);
    scheduleInfoMap.put(scheduleUUID2, schedule2);

    UUID scheduleUUID3 = UUID.randomUUID();
    CreatePitrConfigParams params3 = new CreatePitrConfigParams();
    params3.retentionPeriodInSeconds = 7 * 86400L;
    params3.intervalInSeconds = 86400L;
    params3.universeUUID = defaultUniverse.universeUUID;
    params3.customerUUID = defaultCustomer.uuid;
    params3.keyspaceName = "cassandra";
    params3.tableType = TableType.YQL_TABLE_TYPE;
    long currentTime3 = System.currentTimeMillis();
    PitrConfig pitr3 = PitrConfig.create(scheduleUUID3, params3);
    List<SnapshotInfo> snapshotList3 = new ArrayList<>();
    UUID snapshotUUID31 = UUID.randomUUID();
    long snapshotTime31 = System.currentTimeMillis() + 1000 * 86400L;
    SnapshotInfo snapshot31 =
        new SnapshotInfo(
            snapshotUUID31, snapshotTime31, snapshotTime31 - 1000 * 86400L, State.COMPLETE);
    snapshotList3.add(snapshot31);
    UUID snapshotUUID32 = UUID.randomUUID();
    long snapshotTime32 = System.currentTimeMillis();
    SnapshotInfo snapshot32 = new SnapshotInfo(snapshotUUID32, snapshotTime32, 0L, State.FAILED);
    snapshotList3.add(snapshot32);
    SnapshotScheduleInfo schedule3 =
        new SnapshotScheduleInfo(scheduleUUID3, 86400L, 7L * 86400L, snapshotList3);
    scheduleInfoList.add(schedule3);
    scheduleInfoMap.put(scheduleUUID3, schedule3);

    when(mockListSnapshotSchedulesResponse.getSnapshotScheduleInfoList())
        .thenReturn(scheduleInfoList);
    when(mockClient.listSnapshotSchedules(any())).thenReturn(mockListSnapshotSchedulesResponse);
    Result r = pitrController.listPitrConfigs(defaultCustomer.uuid, defaultUniverse.universeUUID);
    JsonNode json = Json.parse(contentAsString(r));
    assertEquals(OK, r.status());
    assertTrue(json.isArray());
    Iterator<JsonNode> it = json.elements();
    int count = 0;
    while (it.hasNext()) {
      JsonNode schedule = it.next();
      count++;
      long intervalInSecs = schedule.get("scheduleInterval").asLong();
      long retentionDurationInSecs = schedule.get("retentionPeriod").asLong();
      assertEquals(86400L, intervalInSecs);
      assertEquals(7 * 86400L, retentionDurationInSecs);
      UUID scheduleUUID = UUID.fromString(schedule.get("uuid").asText());
      if (!scheduleInfoMap.containsKey(scheduleUUID)) {
        Assert.fail();
      }
      long minTime = schedule.get("minRecoverTimeInMillis").asLong();
      long maxTime = schedule.get("maxRecoverTimeInMillis").asLong();
      String state = schedule.get("state").asText();
      if (scheduleUUID.equals(scheduleUUID1)) {
        assertTrue(System.currentTimeMillis() > minTime);
        assertTrue(currentTime3 < maxTime);
        assertEquals("COMPLETE", state);
      } else if (scheduleUUID.equals(scheduleUUID2)) {
        assertTrue(currentTime3 < maxTime);
        assertEquals("FAILED", state);
      } else if (scheduleUUID.equals(scheduleUUID3)) {
        assertTrue(currentTime3 < minTime && minTime > snapshotTime32 - 1000 * 86400L);
        assertEquals("FAILED", state);
      } else {
        Assert.fail();
      }
    }
    assertEquals(3, count);
    assertAuditEntry(0, defaultCustomer.uuid);
  }

  @Test
  public void testListPitrConfigsWithUniversePaused() {
    UniverseDefinitionTaskParams details = defaultUniverse.getUniverseDetails();
    details.universePaused = true;
    defaultUniverse.setUniverseDetails(details);
    defaultUniverse.save();

    UUID scheduleUUID1 = UUID.randomUUID();
    CreatePitrConfigParams params1 = new CreatePitrConfigParams();
    params1.retentionPeriodInSeconds = 7 * 86400L;
    params1.intervalInSeconds = 86400L;
    params1.universeUUID = defaultUniverse.universeUUID;
    params1.customerUUID = defaultCustomer.uuid;
    params1.keyspaceName = "yugabyte";
    params1.tableType = TableType.PGSQL_TABLE_TYPE;
    PitrConfig pitr1 = PitrConfig.create(scheduleUUID1, params1);

    UUID scheduleUUID2 = UUID.randomUUID();
    CreatePitrConfigParams params2 = new CreatePitrConfigParams();
    params2.retentionPeriodInSeconds = 7 * 86400L;
    params2.intervalInSeconds = 86400L;
    params2.universeUUID = defaultUniverse.universeUUID;
    params2.customerUUID = defaultCustomer.uuid;
    params2.keyspaceName = "postgres";
    params2.tableType = TableType.PGSQL_TABLE_TYPE;
    PitrConfig pitr2 = PitrConfig.create(scheduleUUID2, params2);

    UUID scheduleUUID3 = UUID.randomUUID();
    CreatePitrConfigParams params3 = new CreatePitrConfigParams();
    params3.retentionPeriodInSeconds = 7 * 86400L;
    params3.intervalInSeconds = 86400L;
    params3.universeUUID = defaultUniverse.universeUUID;
    params3.customerUUID = defaultCustomer.uuid;
    params3.keyspaceName = "cassandra";
    params3.tableType = TableType.YQL_TABLE_TYPE;
    long currentTime3 = System.currentTimeMillis();
    PitrConfig pitr3 = PitrConfig.create(scheduleUUID3, params3);

    Result r = pitrController.listPitrConfigs(defaultCustomer.uuid, defaultUniverse.universeUUID);
    JsonNode json = Json.parse(contentAsString(r));
    assertEquals(OK, r.status());
    assertTrue(json.isArray());
    Iterator<JsonNode> it = json.elements();
    int count = 0;
    while (it.hasNext()) {
      JsonNode schedule = it.next();
      count++;
      long intervalInSecs = schedule.get("scheduleInterval").asLong();
      long retentionDurationInSecs = schedule.get("retentionPeriod").asLong();
      assertEquals(86400L, intervalInSecs);
      assertEquals(7 * 86400L, retentionDurationInSecs);
      UUID scheduleUUID = UUID.fromString(schedule.get("uuid").asText());
      long minTime = schedule.get("minRecoverTimeInMillis").asLong();
      long maxTime = schedule.get("maxRecoverTimeInMillis").asLong();
      String state = schedule.get("state").asText();
      if (scheduleUUID.equals(scheduleUUID1)) {
        assertTrue(System.currentTimeMillis() > minTime);
        assertEquals(minTime, maxTime);
        assertEquals("UNKNOWN", state);
      } else if (scheduleUUID.equals(scheduleUUID2)) {
        assertTrue(System.currentTimeMillis() > minTime);
        assertEquals(minTime, maxTime);
        assertEquals("UNKNOWN", state);
      } else if (scheduleUUID.equals(scheduleUUID3)) {
        assertTrue(System.currentTimeMillis() > minTime);
        assertEquals(minTime, maxTime);
        assertEquals("UNKNOWN", state);
      } else {
        Assert.fail();
      }
    }
    assertEquals(3, count);
    assertAuditEntry(0, defaultCustomer.uuid);
  }

  @Test(expected = PlatformServiceException.class)
  public void testListPitrConfigsWithListSnapshotsFailure() throws Exception {
    when(mockClient.listSnapshotSchedules(any())).thenThrow(RuntimeException.class);
    Result r = pitrController.listPitrConfigs(defaultCustomer.uuid, defaultUniverse.universeUUID);
  }

  @Test
  public void testListPitrConfigsWithIncompatibleUniverse() {
    UUID fakeTaskUUID = UUID.randomUUID();
    UniverseDefinitionTaskParams details = defaultUniverse.getUniverseDetails();
    details.getPrimaryCluster().userIntent.ybSoftwareVersion = "2.12.0.0-b111";
    defaultUniverse.setUniverseDetails(details);
    defaultUniverse.save();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    ObjectNode bodyJson = Json.newObject();
    bodyJson.put("retentionPeriodInSeconds", 7 * 86400L);
    bodyJson.put("intervalInSeconds", 86400L);
    Result r =
        assertPlatformException(
            () ->
                pitrController.listPitrConfigs(defaultCustomer.uuid, defaultUniverse.universeUUID));
    JsonNode resultJson = Json.parse(contentAsString(r));
    assertEquals(BAD_REQUEST, r.status());
    verify(mockCommissioner, times(0)).submit(any(), any());
  }

  @Test
  public void testDeletePitrConfig() throws Exception {
    List<SnapshotScheduleInfo> preScheduleInfoList = new ArrayList<>();
    List<UUID> postScheduleUUIDList = new ArrayList<>();

    UUID scheduleUUID1 = UUID.randomUUID();
    CreatePitrConfigParams params1 = new CreatePitrConfigParams();
    params1.retentionPeriodInSeconds = 7 * 86400L;
    params1.intervalInSeconds = 86400L;
    params1.universeUUID = defaultUniverse.universeUUID;
    params1.customerUUID = defaultCustomer.uuid;
    params1.keyspaceName = "yugabyte";
    params1.tableType = TableType.PGSQL_TABLE_TYPE;
    PitrConfig pitr1 = PitrConfig.create(scheduleUUID1, params1);
    List<SnapshotInfo> snapshotList1 = new ArrayList<>();
    SnapshotScheduleInfo schedule1 =
        new SnapshotScheduleInfo(scheduleUUID1, 86400L, 7L * 86400L, snapshotList1);
    preScheduleInfoList.add(schedule1);
    postScheduleUUIDList.add(scheduleUUID1);

    UUID scheduleUUID2 = UUID.randomUUID();
    CreatePitrConfigParams params2 = new CreatePitrConfigParams();
    params2.retentionPeriodInSeconds = 7 * 86400L;
    params2.intervalInSeconds = 86400L;
    params2.universeUUID = defaultUniverse.universeUUID;
    params2.customerUUID = defaultCustomer.uuid;
    params2.keyspaceName = "postgres";
    params2.tableType = TableType.PGSQL_TABLE_TYPE;
    PitrConfig pitr2 = PitrConfig.create(scheduleUUID2, params2);
    List<SnapshotInfo> snapshotList2 = new ArrayList<>();
    SnapshotScheduleInfo schedule2 =
        new SnapshotScheduleInfo(scheduleUUID2, 86400L, 7L * 86400L, snapshotList2);
    preScheduleInfoList.add(schedule2);
    postScheduleUUIDList.add(scheduleUUID2);

    UUID scheduleUUID3 = UUID.randomUUID();
    CreatePitrConfigParams params3 = new CreatePitrConfigParams();
    params3.retentionPeriodInSeconds = 7 * 86400L;
    params3.intervalInSeconds = 86400L;
    params3.universeUUID = defaultUniverse.universeUUID;
    params3.customerUUID = defaultCustomer.uuid;
    params3.keyspaceName = "cassandra";
    params3.tableType = TableType.YQL_TABLE_TYPE;
    PitrConfig pitr3 = PitrConfig.create(scheduleUUID3, params3);
    List<SnapshotInfo> snapshotList3 = new ArrayList<>();
    SnapshotScheduleInfo schedule3 =
        new SnapshotScheduleInfo(scheduleUUID3, 86400L, 7L * 86400L, snapshotList3);
    preScheduleInfoList.add(schedule3);

    Map<String, String> flashData = Collections.emptyMap();
    Map<String, Object> argData =
        ImmutableMap.of("user", new UserWithFeatures().setUser(defaultUser));
    Http.Request request = mock(Http.Request.class);
    when(request.path())
        .thenReturn(
            "/api/customers/"
                + defaultCustomer.uuid
                + "/universes/"
                + defaultUniverse.universeUUID
                + "/pitr_config/"
                + scheduleUUID3);
    when(request.method()).thenReturn("DELETE");
    Long id = 1L;
    play.api.mvc.RequestHeader header = mock(play.api.mvc.RequestHeader.class);
    Http.Context context =
        new Http.Context(id, header, request, flashData, flashData, argData, contextComponents());
    Http.Context.current.set(context);

    when(mockListSnapshotSchedulesResponse.getSnapshotScheduleInfoList())
        .thenReturn(preScheduleInfoList);
    when(mockClient.listSnapshotSchedules(any())).thenReturn(mockListSnapshotSchedulesResponse);
    when(mockClient.deleteSnapshotSchedule(scheduleUUID3))
        .thenReturn(mockDeleteSnapshotScheduleResponse);
    Result r =
        pitrController.deletePitrConfig(
            defaultCustomer.uuid, defaultUniverse.universeUUID, scheduleUUID3);
    JsonNode json = Json.parse(contentAsString(r));
    assertAuditEntry(1, defaultCustomer.uuid);
    assertEquals(OK, r.status());

    List<PitrConfig> configs = PitrConfig.getAll();
    assertEquals(2, configs.size());
    for (PitrConfig config : configs) {
      if (!postScheduleUUIDList.contains(config.uuid)) {
        Assert.fail();
      }
    }
  }

  @Test
  public void testDeletePitrConfigWithUniversePaused() {
    UUID scheduleUUID3 = UUID.randomUUID();
    CreatePitrConfigParams params3 = new CreatePitrConfigParams();
    params3.retentionPeriodInSeconds = 7 * 86400L;
    params3.intervalInSeconds = 86400L;
    params3.universeUUID = defaultUniverse.universeUUID;
    params3.customerUUID = defaultCustomer.uuid;
    params3.keyspaceName = "cassandra";
    params3.tableType = TableType.YQL_TABLE_TYPE;
    PitrConfig pitr3 = PitrConfig.create(scheduleUUID3, params3);

    UniverseDefinitionTaskParams details = defaultUniverse.getUniverseDetails();
    details.universePaused = true;
    defaultUniverse.setUniverseDetails(details);
    defaultUniverse.save();
    ObjectNode bodyJson = Json.newObject();
    bodyJson.put("retentionPeriodInSeconds", 7 * 86400L);
    bodyJson.put("intervalInSeconds", 86400L);
    Result r =
        assertPlatformException(
            () ->
                pitrController.deletePitrConfig(
                    defaultCustomer.uuid, defaultUniverse.universeUUID, scheduleUUID3));
    JsonNode resultJson = Json.parse(contentAsString(r));
    assertEquals(BAD_REQUEST, r.status());
    verify(mockCommissioner, times(0)).submit(any(), any());
  }

  @Test
  public void testDeletePitrConfigWithIncompatibleUniverse() {
    UUID scheduleUUID3 = UUID.randomUUID();
    UniverseDefinitionTaskParams details = defaultUniverse.getUniverseDetails();
    details.getPrimaryCluster().userIntent.ybSoftwareVersion = "2.12.0.0-b111";
    defaultUniverse.setUniverseDetails(details);
    defaultUniverse.save();
    Result r =
        assertPlatformException(
            () ->
                pitrController.deletePitrConfig(
                    defaultCustomer.uuid, defaultUniverse.universeUUID, scheduleUUID3));
    JsonNode resultJson = Json.parse(contentAsString(r));
    assertEquals(BAD_REQUEST, r.status());
    verify(mockCommissioner, times(0)).submit(any(), any());
  }

  @Test
  public void testPerformPitr() throws Exception {
    List<SnapshotScheduleInfo> scheduleInfoList = new ArrayList<>();
    UUID fakeTaskUUID = UUID.randomUUID();
    UUID pitrConfigUUID = UUID.randomUUID();
    CreatePitrConfigParams params3 = new CreatePitrConfigParams();
    params3.retentionPeriodInSeconds = 7 * 86400L;
    params3.intervalInSeconds = 86400L;
    params3.universeUUID = defaultUniverse.universeUUID;
    params3.customerUUID = defaultCustomer.uuid;
    params3.keyspaceName = "cassandra";
    params3.tableType = TableType.YQL_TABLE_TYPE;
    PitrConfig pitr3 = PitrConfig.create(pitrConfigUUID, params3);
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    UUID snapshotUUID = UUID.randomUUID();
    long snapshotTime = System.currentTimeMillis() + 2 * 1000 * 86400L;
    List<SnapshotInfo> snapshotList = new ArrayList<>();
    SnapshotInfo snapshot =
        new SnapshotInfo(snapshotUUID, snapshotTime, snapshotTime - 1000 * 86400L, State.COMPLETE);
    snapshotList.add(snapshot);
    SnapshotScheduleInfo schedule =
        new SnapshotScheduleInfo(pitrConfigUUID, 86400L, 7L * 86400L, snapshotList);
    scheduleInfoList.add(schedule);
    when(mockListSnapshotSchedulesResponse.getSnapshotScheduleInfoList())
        .thenReturn(scheduleInfoList);
    when(mockClient.listSnapshotSchedules(any())).thenReturn(mockListSnapshotSchedulesResponse);

    RestoreSnapshotScheduleParams params = new RestoreSnapshotScheduleParams();
    params.universeUUID = defaultUniverse.universeUUID;
    params.pitrConfigUUID = pitrConfigUUID;
    params.restoreTimeInMillis = System.currentTimeMillis();

    ObjectNode bodyJson = Json.newObject();
    bodyJson.put("retentionPeriodInSeconds", 7 * 86400L);
    bodyJson.put("intervalInSeconds", 86400L);
    Result r = performPitr(defaultUniverse.universeUUID, Json.toJson(params));
    assertOk(r);
    JsonNode resultJson = Json.parse(contentAsString(r));
    assertValue(resultJson, "taskUUID", fakeTaskUUID.toString());
    assertEquals(OK, r.status());
    verify(mockCommissioner, times(1)).submit(any(), any());
    assertAuditEntry(1, defaultCustomer.uuid);
  }

  @Test
  public void testPerformPitrWithNoUniverse() throws Exception {
    RestoreSnapshotScheduleParams params = new RestoreSnapshotScheduleParams();
    params.universeUUID = defaultUniverse.universeUUID;
    params.pitrConfigUUID = UUID.randomUUID();
    params.restoreTimeInMillis = System.currentTimeMillis();

    Result r = assertPlatformException(() -> performPitr(UUID.randomUUID(), Json.toJson(params)));
    JsonNode resultJson = Json.parse(contentAsString(r));
    assertEquals(BAD_REQUEST, r.status());
    verify(mockCommissioner, times(0)).submit(any(), any());
    assertAuditEntry(0, defaultCustomer.uuid);
  }

  @Test
  public void testPerformPitrWithUniversePaused() throws Exception {
    RestoreSnapshotScheduleParams params = new RestoreSnapshotScheduleParams();
    params.universeUUID = defaultUniverse.universeUUID;
    params.pitrConfigUUID = UUID.randomUUID();
    params.restoreTimeInMillis = System.currentTimeMillis();

    UniverseDefinitionTaskParams details = defaultUniverse.getUniverseDetails();
    details.universePaused = true;
    defaultUniverse.setUniverseDetails(details);
    defaultUniverse.save();

    Result r =
        assertPlatformException(
            () -> performPitr(defaultUniverse.universeUUID, Json.toJson(params)));
    JsonNode resultJson = Json.parse(contentAsString(r));
    assertEquals(BAD_REQUEST, r.status());
    verify(mockCommissioner, times(0)).submit(any(), any());
    assertAuditEntry(0, defaultCustomer.uuid);
  }

  @Test
  public void testPerformPitrWithIncompatibleUniverse() throws Exception {
    RestoreSnapshotScheduleParams params = new RestoreSnapshotScheduleParams();
    params.universeUUID = defaultUniverse.universeUUID;
    params.pitrConfigUUID = UUID.randomUUID();
    params.restoreTimeInMillis = System.currentTimeMillis();

    UniverseDefinitionTaskParams details = defaultUniverse.getUniverseDetails();
    details.getPrimaryCluster().userIntent.ybSoftwareVersion = "2.12.0.0-b111";
    defaultUniverse.setUniverseDetails(details);
    defaultUniverse.save();

    Result r =
        assertPlatformException(
            () -> performPitr(defaultUniverse.universeUUID, Json.toJson(params)));
    JsonNode resultJson = Json.parse(contentAsString(r));
    assertEquals(BAD_REQUEST, r.status());
    verify(mockCommissioner, times(0)).submit(any(), any());
    assertAuditEntry(0, defaultCustomer.uuid);
  }
}
