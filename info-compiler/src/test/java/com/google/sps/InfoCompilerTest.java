// Copyright 2019 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.sps.infocompiler;

import com.google.cloud.datastore.BooleanValue;
import com.google.cloud.datastore.Datastore;
import com.google.cloud.datastore.Entity;
import com.google.cloud.datastore.Value;
import com.google.cloud.datastore.Key;
import com.google.cloud.datastore.Query;
import com.google.cloud.datastore.QueryResults;
import com.google.cloud.datastore.StringValue;
import com.google.cloud.datastore.testing.LocalDatastoreHelper;
import com.google.cloud.datastore.Value;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeoutException;
import java.util.Date;
import java.util.List;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;

/**
 * A tester for the location-based information compiler.
 */
@RunWith(JUnit4.class)
public final class InfoCompilerTest {
  private static final String CIVIC_INFO_API_KEY = Config.CIVIC_INFO_API_KEY;
  private static final String ELECTION_QUERY_URL =
      String.format("https://www.googleapis.com/civicinfo/v2/elections?key=%s",
                    CIVIC_INFO_API_KEY);
  // @see Sample JSON structure:
  //     https://developers.google.com/civic-information/docs/using_api#electionquery-example.
  private static final String ELECTION_RESPONSE =
      "{" +
      " \"kind\": \"civicinfo#electionsqueryresponse\"," +
      " \"elections\": [" +
      "  {" +
      "   \"id\": \"2000\"," +
      "   \"name\": \"VIP Test Election\"," +
      "   \"electionDay\": \"2013-06-06\"" +
      "  }" +
      " ]" +
      "}";
  private static final boolean PLACEHOLDER_INCUMBENCY = false;
  private static JsonObject ELECTION_JSON;
  private static JsonObject SINGLE_CONTEST_JSON;
  private static InfoCompiler infoCompiler;
  private static LocalDatastoreHelper datastoreHelper;
  private static Datastore datastore;

  @BeforeClass
  public static void initialize() throws InterruptedException, IOException {
    datastoreHelper = LocalDatastoreHelper.create();
    datastoreHelper.start();
    datastore = datastoreHelper.getOptions().getService();
    infoCompiler = new InfoCompiler(datastore);

    ELECTION_JSON = new JsonObject();
    ELECTION_JSON.addProperty("kind", "civicinfo#electionsqueryresponse");
    JsonArray elections = new JsonArray(1);
    JsonObject election = new JsonObject();
    election.addProperty("id", "2000");
    election.addProperty("name", "VIP Test Election");
    election.addProperty("electionDay", "2013-06-06");
    elections.add(election);
    ELECTION_JSON.add("elections", elections);

    SINGLE_CONTEST_JSON = new JsonObject();
    SINGLE_CONTEST_JSON.addProperty("office", "Governor");
    JsonArray candidates = new JsonArray(1);
    JsonObject candidate = new JsonObject();
    candidate.addProperty("name", "Andrew Cuomo");
    candidate.addProperty("party", "Democratic");
    candidates.add(candidate);
    SINGLE_CONTEST_JSON.add("candidates", candidates);
  }

  /**
   * Resets the internal state of the Datastore emulator and then {@code datastore}. Also resets
   * {@code infoCompiler}. We choose to reset, instead of creating/destroying the Datastore
   * emulator at each test, because {@code datastoreHelper.stop()} sometimes generates a {@code
   * java.net.ConnectException}, when making HTTP requests. Hence we try to limit the number of
   * times {@code datastoreHelper} is created/destroyed.
   */
  @Before
  public void resetDatastore() throws IOException {
    datastoreHelper.reset();
    datastore = datastoreHelper.getOptions().getService();
    infoCompiler = new InfoCompiler(datastore);
  }

  @Test
  public void queryCivicInformation_succeedWithMockResponse() throws Exception {
    // Query the Civic Information API with a mock HTTP client + mock callback function of type
    // {@code ResponseHandler<String>} that converts any {@code HttpResponse} response to {@code
    // ELECTION_RESPONSE}, and subsequently convert {@code ELECTION_RESPONSE} to {@code json}.
    // {@code httpGet} is irrelevant in this test, since {@code execute()} is mocked as above.
    // Since String {@code ELECTION_RESPONSE} and JsonObject {@code ELECTION_JSON} match in
    // content, {@code json} should be exactly the same as {@code ELECTION_JSON}. Here, we don't
    // repeatedly test the same thing with {@code SINGLE_CONTEST_JSON}.
    CloseableHttpClient httpClient = mock(CloseableHttpClient.class);
    HttpGet httpGet = new HttpGet(ELECTION_QUERY_URL);
    ArgumentCaptor<ResponseHandler<String>> argumentCaptor =
        ArgumentCaptor.forClass(ResponseHandler.class);
    when(httpClient.execute(anyObject(), argumentCaptor.capture())).thenReturn(ELECTION_RESPONSE);
    JsonObject json =
        infoCompiler.requestHttpAndBuildJsonResponseFromCivicInformation(httpClient, httpGet);
    Assert.assertEquals(json, ELECTION_JSON);
  }

  @Test
  public void storeBaseElectionInDatabase_checkDatastoreEntityConstructionFromJson()
      throws IOException {
    // Parse and re-structure base election information from {@code ELECTION_JSON}'s
    // "elections" section and store the corresponding entity in the database. Said
    // entity should contain information that is consistent with that in {@code ELECTION_JSON}.
    // A Datastore emulator is used to simulate Datastore operations, as opposed to Mockito mocks.
    JsonObject election = (JsonObject) ELECTION_JSON.getAsJsonArray("elections").get(0);
    String[] yearMonthDay = election.get("electionDay").getAsString().split("-");
    Date date = new Date(
        Integer.parseInt(yearMonthDay[0]) - 1900,
        Integer.parseInt(yearMonthDay[1]) - 1,
        Integer.parseInt(yearMonthDay[2]),
        4,
        0);

    infoCompiler.storeBaseElectionInDatabase(election);
    Query<Entity> query =
        Query.newEntityQueryBuilder()
            .setKind("Election")
            .build();
    QueryResults<Entity> queryResult = datastore.run(query);
    Assert.assertTrue(queryResult.hasNext());
    Entity electionEntity = queryResult.next();
    Assert.assertFalse(queryResult.hasNext());
    Assert.assertEquals(electionEntity.getKey().getName(), election.get("name").getAsString());
    Assert.assertEquals(electionEntity.getString("queryId"), election.get("id").getAsString());
    Assert.assertEquals(electionEntity.getTimestamp("date").toDate(), date);
    Assert.assertEquals(electionEntity.getList("candidatePositions"), Arrays.asList());
    Assert.assertEquals(electionEntity.getList("candidateIds"), Arrays.asList());
    Assert.assertEquals(electionEntity.getList("candidateIncumbency"), Arrays.asList());
  }

  @Test
  public void storeElectionContestInDatabase_checkDatastoreEntityConstructionFromJson()
      throws IOException {
    // Parse and re-structure election/position/candidate information from {@code
    // SINGLE_CONTEST_JSON}. Create a correponding candidate entity and update the existing
    // election entity in the database. Said entities should contain information that is consistent
    // with that in {@code SINGLE_CONTEST_JSON}. A Datastore emulator is used to simulate Datastore
    // operations, as opposed to Mockito mocks.
    // This method relies on the election entity created by {@code storeBaseElectionInDatabase()}
    // and thus assumes the correctness of said method. This method avoids repeating any tests
    // executed by {@code storeBaseElectionInDatabase_checkDatastoreEntityConstructionFromJson()}.
    JsonObject election = (JsonObject) ELECTION_JSON.getAsJsonArray("elections").get(0);
    JsonObject candidate = (JsonObject) SINGLE_CONTEST_JSON.getAsJsonArray("candidates").get(0);
    Long candidateId = new Long(candidate.get("name").getAsString().hashCode()
                                + candidate.get("party").getAsString().hashCode());

    infoCompiler.storeBaseElectionInDatabase(election);
    infoCompiler.storeElectionContestInDatabase(election.get("id").getAsString(),
                                                SINGLE_CONTEST_JSON);
    // Check data additions to the election entity.
    Query<Entity> electionQuery =
        Query.newEntityQueryBuilder()
            .setKind("Election")
            .build();
    Entity electionEntity = datastore.run(electionQuery).next();
    List<Value<String>> candidatePositions =
        new ArrayList<>(electionEntity.getList("candidatePositions"));
    Assert.assertEquals(candidatePositions.size(), 1);
    Assert.assertEquals(candidatePositions,
                        Arrays.asList(
                            StringValue.newBuilder(
                                SINGLE_CONTEST_JSON.get("office").getAsString()).build()));
    List<Value<String>> candidateIds =
        new ArrayList<>(electionEntity.getList("candidateIds"));
    Assert.assertEquals(candidateIds.size(), 1);
    Assert.assertEquals(candidateIds,
                        Arrays.asList(StringValue.newBuilder(candidateId.toString()).build()));
    List<Value<Boolean>> candidateIncumbency =
        new ArrayList<>(electionEntity.getList("candidateIncumbency"));
    Assert.assertEquals(candidateIncumbency.size(), 1);
    Assert.assertEquals(candidateIncumbency,
                        Arrays.asList(BooleanValue.newBuilder(PLACEHOLDER_INCUMBENCY).build()));
    // Check candidate data.
    Query<Entity> candidateQuery =
        Query.newEntityQueryBuilder()
            .setKind("Candidate")
            .build();
    QueryResults<Entity> queryResult = datastore.run(candidateQuery);
    Assert.assertTrue(queryResult.hasNext());
    Entity candidateEntity = queryResult.next();
    Assert.assertFalse(queryResult.hasNext());
    Assert.assertEquals(candidateEntity.getKey().getId(), candidateId);
    Assert.assertEquals(candidateEntity.getString("name"), candidate.get("name").getAsString());
    Assert.assertEquals(candidateEntity.getString("partyAffiliation"),
                        candidate.get("party").getAsString() + " Party");
  }

  // Integrated tests.
  //
  // @Test
  // public void queryAndStoreBaseElectionInfo() {
  //   // Execute the entire process of querying and storing base election information. Should execute
  //   // without exceptions and the database should be populated correctly. This confirms the validity
  //   // of the query URLs and HTTP requests to the Civic Information API.
  //   // This is an integrated test.
  //   infoCompiler.queryAndStoreBaseElectionInfo();
  // }
  // @Test
  // public void queryAndStoreElectionContestInfo() {
  //   // Execute the entire process of querying and storing election/position/candidate information.
  //   // This step is intended to run right after {@code queryAndStoreBaseElectionInfo()}.
  //   // Should execute without exceptions and the database should be populated correctly. This
  //   // confirms the validity of the query URLs and HTTP requests to the Civic Information API.
  //   // This is an integrated test.
  //   infoCompiler.queryAndStoreElectionContestInfo();
  // }
  // @Test
  // public void queryAndStoreLocationBasedInfo() {
  //   // Execute the entire process of querying and storing location-based information. Should
  //   // execute without exceptions and the database should be populated correctly. This confirms the
  //   // validity of the query URLs and HTTP requests to the Civic Information API.
  //   // This is an integrated test.
  //   infoCompiler.queryAndStoreBaseElectionInfo();
  //   infoCompiler.queryAndStoreElectionContestInfo();
  // }

  @AfterClass
  public static void cleanup() throws InterruptedException, IOException, TimeoutException {
    datastoreHelper.stop();
  }
}