// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//

#include <chrono>

#include "yb/client/schema.h"
#include "yb/client/table.h"
#include "yb/client/table_creator.h"
#include "yb/client/yb_table_name.h"

#include "yb/common/partition.h"

#include "yb/integration-tests/external_mini_cluster.h"
#include "yb/integration-tests/mini_cluster.h"
#include "yb/integration-tests/yb_mini_cluster_test_base.h"

#include "yb/master/master-path-handlers.h"
#include "yb/master/mini_master.h"

#include "yb/tools/yb-admin_client.h"

#include "yb/tserver/mini_tablet_server.h"

#include "yb/util/backoff_waiter.h"
#include "yb/util/curl_util.h"
#include "yb/util/jsonreader.h"
#include "yb/util/random_util.h"
#include "yb/util/result.h"
#include "yb/util/test_macros.h"

DECLARE_int32(tserver_unresponsive_timeout_ms);
DECLARE_int32(heartbeat_interval_ms);
DECLARE_string(TEST_master_extra_list_host_port);

DECLARE_int32(follower_unavailable_considered_failed_sec);

namespace yb {
namespace master {

using std::string;

const std::string kKeyspaceName("my_keyspace");
const uint kNumMasters(3);
const uint kNumTablets(3);

template <class T>
class MasterPathHandlersBaseItest : public YBMiniClusterTestBase<T> {
 public:
  void DoTearDown() override {
    cluster_->Shutdown();
  }

 protected:
  void TestUrl(const string& query_path, faststring* result) {
    const string tables_url = master_http_url_ + query_path;
    EasyCurl curl;
    ASSERT_OK(curl.FetchURL(tables_url, result));
  }

  virtual int num_masters() const {
    return kNumMasters;
  }

  using YBMiniClusterTestBase<T>::cluster_;
  string master_http_url_;
};

class MasterPathHandlersItest : public MasterPathHandlersBaseItest<MiniCluster> {
 public:
  void SetUp() override {
    YBMiniClusterTestBase::SetUp();
    MiniClusterOptions opts;
    // Set low heartbeat timeout.
    FLAGS_tserver_unresponsive_timeout_ms = 5000;
    opts.num_tablet_servers = kNumTablets;
    opts.num_masters = num_masters();
    cluster_.reset(new MiniCluster(opts));
    ASSERT_OK(cluster_->Start());

    Endpoint master_http_endpoint =
        ASSERT_RESULT(cluster_->GetLeaderMiniMaster())->bound_http_addr();
    master_http_url_ = "http://" + AsString(master_http_endpoint);
  }
};

bool verifyTServersAlive(int n, const string& result) {
  size_t pos = 0;
  for (int i = 0; i < n; i++) {
    pos = result.find(kTserverAlive, pos + 1);
    if (pos == string::npos) {
      return false;
    }
  }
  return result.find(kTserverAlive, pos + 1) == string::npos;
}

TEST_F(MasterPathHandlersItest, TestMasterPathHandlers) {
  faststring result;
  TestUrl("/table?id=1", &result);
  TestUrl("/tablet-servers", &result);
  TestUrl("/tables", &result);
  TestUrl("/dump-entities", &result);
  TestUrl("/cluster-config", &result);
  TestUrl("/tablet-replication", &result);
  TestUrl("/load-distribution", &result);
}

TEST_F(MasterPathHandlersItest, TestDeadTServers) {
  // Shutdown tserver and wait for heartbeat timeout.
  cluster_->mini_tablet_server(0)->Shutdown();
  std::this_thread::sleep_for(std::chrono::milliseconds(2 * FLAGS_tserver_unresponsive_timeout_ms));

  // Check UI page.
  faststring result;
  TestUrl("/tablet-servers", &result);
  const string &result_str = result.ToString();
  ASSERT_TRUE(verifyTServersAlive(2, result_str));

  // Now verify dead.
  size_t pos = result_str.find(kTserverDead, 0);
  ASSERT_TRUE(pos != string::npos);
  ASSERT_TRUE(result_str.find(kTserverDead, pos + 1) == string::npos);

  // Startup the tserver and wait for heartbeats.
  ASSERT_OK(cluster_->mini_tablet_server(0)->Start());

  ASSERT_OK(WaitFor([&]() -> bool {
    TestUrl("/tablet-servers", &result);
    return verifyTServersAlive(3, result.ToString());
  }, MonoDelta::FromSeconds(10), "Waiting for tserver heartbeat to master"));
}

TEST_F(MasterPathHandlersItest, TestTabletReplicationEndpoint) {
  auto client = ASSERT_RESULT(cluster_->CreateClient());
  ASSERT_OK(client->CreateNamespaceIfNotExists(kKeyspaceName));

  // Create table.
  // TODO(5016): Consolidate into some standardized helper code.
  client::YBTableName table_name(YQL_DATABASE_CQL, kKeyspaceName, "test_table");
  client::YBSchema schema;
  client::YBSchemaBuilder b;
  b.AddColumn("key")->Type(INT32)->NotNull()->PrimaryKey();
  b.AddColumn("int_val")->Type(INT32)->NotNull();
  b.AddColumn("string_val")->Type(STRING)->NotNull();
  ASSERT_OK(b.Build(&schema));
  std::unique_ptr<client::YBTableCreator> table_creator(client->NewTableCreator());
  ASSERT_OK(table_creator->table_name(table_name)
      .schema(&schema)
      .hash_schema(YBHashSchema::kMultiColumnHash)
      .Create());
  std::shared_ptr<client::YBTable> table;
  ASSERT_OK(client->OpenTable(table_name, &table));

  // Choose a tablet to orphan and take note of the servers which are leaders/followers for this
  // tablet.
  google::protobuf::RepeatedPtrField<TabletLocationsPB> tablets;
  ASSERT_OK(client->GetTabletsFromTableId(table->id(), kNumTablets, &tablets));
  std::vector<yb::tserver::MiniTabletServer *> followers;
  yb::tserver::MiniTabletServer* leader = nullptr;
  auto orphan_tablet = tablets.Get(0);
  for (const auto& replica : orphan_tablet.replicas()) {
    const auto uuid = replica.ts_info().permanent_uuid();
    auto* tserver = cluster_->find_tablet_server(uuid);
    ASSERT_NOTNULL(tserver);
    if (replica.role() == PeerRole::LEADER) {
      leader = tserver;
    } else {
      followers.push_back(tserver);
    }
    // Shutdown all tservers.
    tserver->Shutdown();
  }
  ASSERT_NOTNULL(leader);

  // Restart the server which was previously the leader of the now orphaned tablet.
  ASSERT_OK(leader->Start());
  // Sleep here to give the master's catalog_manager time to receive heartbeat from "leader".
  std::this_thread::sleep_for(std::chrono::milliseconds(6 * FLAGS_heartbeat_interval_ms));

  // Call endpoint and validate format of response.
  faststring result;
  TestUrl("/api/v1/tablet-replication", &result);

  JsonReader r(result.ToString());
  ASSERT_OK(r.Init());
  const rapidjson::Value* json_obj = nullptr;
  EXPECT_OK(r.ExtractObject(r.root(), NULL, &json_obj));
  EXPECT_EQ(rapidjson::kObjectType, CHECK_NOTNULL(json_obj)->GetType());
  EXPECT_TRUE(json_obj->HasMember("leaderless_tablets"));
  EXPECT_EQ(rapidjson::kArrayType, (*json_obj)["leaderless_tablets"].GetType());
  const rapidjson::Value::ConstArray tablets_json = (*json_obj)["leaderless_tablets"].GetArray();
  std::vector<std::string> leaderless_tablet_uuids;
  for (const auto& tablet_json : tablets_json) {
    EXPECT_EQ(rapidjson::kObjectType, tablet_json.GetType());
    EXPECT_TRUE(tablet_json.HasMember("table_uuid"));
    EXPECT_EQ(rapidjson::kStringType, tablet_json["table_uuid"].GetType());
    EXPECT_TRUE(tablet_json.HasMember("tablet_uuid"));
    EXPECT_EQ(rapidjson::kStringType, tablet_json["tablet_uuid"].GetType());
  }

  auto has_orphan_tablet_result = std::any_of(
      tablets_json.begin(), tablets_json.end(),
      [&orphan_tablet](const auto& tablet_json) {
        return tablet_json["tablet_uuid"].GetString() == orphan_tablet.tablet_id();
      });
  EXPECT_TRUE(has_orphan_tablet_result) << "Expected to find orphan_tablet in leaderless tablets.";

  // YBMiniClusterTestBase test-end verification will fail if the cluster is up with stopped nodes.
  cluster_->Shutdown();
}

TEST_F(MasterPathHandlersItest, TestTabletUnderReplicationEndpoint) {
  // Set test specific flag
  FLAGS_follower_unavailable_considered_failed_sec = 30;

  auto client = ASSERT_RESULT(cluster_->CreateClient());
  ASSERT_OK(client->CreateNamespaceIfNotExists(kKeyspaceName));

  // Create table.
  client::YBTableName table_name(YQL_DATABASE_CQL, kKeyspaceName, "test_table");
  client::YBSchema schema;
  client::YBSchemaBuilder b;
  b.AddColumn("key")->Type(INT32)->NotNull()->PrimaryKey();
  b.AddColumn("int_val")->Type(INT32)->NotNull();
  b.AddColumn("string_val")->Type(STRING)->NotNull();
  ASSERT_OK(b.Build(&schema));
  std::unique_ptr<client::YBTableCreator> table_creator(client->NewTableCreator());
  ASSERT_OK(table_creator->table_name(table_name)
      .schema(&schema)
      .hash_schema(YBHashSchema::kMultiColumnHash)
      .Create());
  std::shared_ptr<client::YBTable> table;
  ASSERT_OK(client->OpenTable(table_name, &table));

  // Get all the tablets of this table and store them
  google::protobuf::RepeatedPtrField<TabletLocationsPB> tablets;
  ASSERT_OK(client->GetTabletsFromTableId(table->id(), kNumTablets, &tablets));

  std::vector<std::string> tIds;
  bool isTestTrue = true;

  int numTablets = tablets.size();
  for(int i = 0; i < numTablets; i++) {
    auto tablet = tablets.Get(i);
    tIds.push_back(tablet.tablet_id());
  }

  // Now kill one of the servers
  // Since the replication factor is 3 and the number
  // of nodes is also 3, all the tablets
  // of this table should become under replicated
  cluster_->mini_tablet_server(0)->Shutdown();
  // Wait for 3*30 secs just to be safe
  std::this_thread::sleep_for(std::chrono::milliseconds(
    3 * FLAGS_follower_unavailable_considered_failed_sec * 1000));

  // Call endpoint and validate format of response.
  faststring result;
  TestUrl("/api/v1/tablet-under-replication", &result);

  JsonReader r(result.ToString());
  ASSERT_OK(r.Init());
  const rapidjson::Value* json_obj = nullptr;
  EXPECT_OK(r.ExtractObject(r.root(), NULL, &json_obj));
  EXPECT_EQ(rapidjson::kObjectType, CHECK_NOTNULL(json_obj)->GetType());
  EXPECT_TRUE(json_obj->HasMember("underreplicated_tablets"));
  EXPECT_EQ(rapidjson::kArrayType, (*json_obj)["underreplicated_tablets"].GetType());
  const rapidjson::Value::ConstArray tablets_json =
      (*json_obj)["underreplicated_tablets"].GetArray();

  for (const auto& tablet_json : tablets_json) {
    EXPECT_EQ(rapidjson::kObjectType, tablet_json.GetType());
    EXPECT_TRUE(tablet_json.HasMember("table_uuid"));
    EXPECT_EQ(rapidjson::kStringType, tablet_json["table_uuid"].GetType());
    EXPECT_TRUE(tablet_json.HasMember("tablet_uuid"));
    EXPECT_EQ(rapidjson::kStringType, tablet_json["tablet_uuid"].GetType());
  }

  // These tablets should be present in the json response
  for(const std::string &id : tIds) {
    isTestTrue = isTestTrue && std::any_of(tablets_json.begin(), tablets_json.end(),
                      [&id](const auto &tablet_json) {
                          return tablet_json["tablet_uuid"].GetString() == id;
                      });
  }

  EXPECT_TRUE(isTestTrue) << "Expected to find under-replicated tablets.";

  // YBMiniClusterTestBase test-end verification will fail if the cluster is up with stopped nodes.
  cluster_->Shutdown();
}

class MultiMasterPathHandlersItest : public MasterPathHandlersItest {
 public:
  int num_masters() const override {
    return 3;
  }
};

TEST_F_EX(MasterPathHandlersItest, Forward, MultiMasterPathHandlersItest) {
  FLAGS_TEST_master_extra_list_host_port = RandomHumanReadableString(16) + ".com";
  EasyCurl curl;
  faststring content;
  for (size_t i = 0; i != cluster_->num_masters(); ++i) {
    auto url = Format("http://$0/tablet-servers", cluster_->mini_master(i)->bound_http_addr());
    content.clear();
    ASSERT_OK(curl.FetchURL(url, &content));
  }
}

class MasterPathHandlersExternalItest : public MasterPathHandlersBaseItest<ExternalMiniCluster> {
 public:
  void SetUp() override {
    YBMiniClusterTestBase::SetUp();
    ExternalMiniClusterOptions opts;
    // Set low heartbeat timeout.
    FLAGS_tserver_unresponsive_timeout_ms = 5000;
    opts.num_tablet_servers = kNumTablets;
    opts.num_masters = num_masters();
    cluster_.reset(new ExternalMiniCluster(opts));
    ASSERT_OK(cluster_->Start());

    HostPort master_http_endpoint = cluster_->master(0)->bound_http_hostport();
    master_http_url_ = "http://" + ToString(master_http_endpoint);
  }
};

TEST_F_EX(MasterPathHandlersItest, TestTablePlacementInfo, MasterPathHandlersExternalItest) {
  auto client = ASSERT_RESULT(cluster_->CreateClient());
  ASSERT_OK(client->CreateNamespaceIfNotExists(kKeyspaceName));

  // Create table.
  // TODO(5016): Consolidate into some standardized helper code.
  client::YBTableName table_name(YQL_DATABASE_CQL, kKeyspaceName, "test_table");
  client::YBSchema schema;
  client::YBSchemaBuilder b;
  b.AddColumn("key")->Type(INT32)->NotNull()->PrimaryKey();
  b.AddColumn("int_val")->Type(INT32)->NotNull();
  b.AddColumn("string_val")->Type(STRING)->NotNull();
  ASSERT_OK(b.Build(&schema));
  std::unique_ptr<client::YBTableCreator> table_creator(client->NewTableCreator());
  ASSERT_OK(table_creator->table_name(table_name)
      .schema(&schema)
      .hash_schema(YBHashSchema::kMultiColumnHash)
      .Create());
  std::shared_ptr<client::YBTable> table;
  ASSERT_OK(client->OpenTable(table_name, &table));

  // Verify replication info is empty.
  faststring result;
  auto url = Format("/table?id=$0", table->id());
  TestUrl(url, &result);
  const string& result_str = result.ToString();
  size_t pos = result_str.find("Replication Info", 0);
  ASSERT_NE(pos, string::npos);
  ASSERT_EQ(result_str.find("live_replicas", pos + 1), string::npos);

  // Verify cluster level replication info.
  auto yb_admin_client_ = std::make_unique<yb::tools::enterprise::ClusterAdminClient>(
    cluster_->GetMasterAddresses(), MonoDelta::FromSeconds(30));
  ASSERT_OK(yb_admin_client_->Init());
  ASSERT_OK(yb_admin_client_->ModifyPlacementInfo("cloud.region.zone", 3, "table_uuid"));
  TestUrl(url, &result);
  const string& cluster_str = result.ToString();
  pos = cluster_str.find("Replication Info", 0);
  ASSERT_NE(pos, string::npos);
  pos = cluster_str.find("placement_zone", pos + 1);
  ASSERT_NE(pos, string::npos);
  ASSERT_EQ(cluster_str.substr(pos + 17, 4), "zone");

  // Verify table level replication info.
  ASSERT_OK(yb_admin_client_->ModifyTablePlacementInfo(
    table->name(), "cloud.region.anotherzone", 3, "table_uuid"));
  TestUrl(url, &result);
  const string& table_str = result.ToString();
  pos = table_str.find("Replication Info", 0);
  ASSERT_NE(pos, string::npos);
  pos = table_str.find("placement_zone", pos + 1);
  ASSERT_NE(pos, string::npos);
  ASSERT_EQ(table_str.substr(pos + 17, 11), "anotherzone");
}

} // namespace master
} // namespace yb
