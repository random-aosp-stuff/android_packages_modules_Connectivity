#  Copyright (C) 2024 The Android Open Source Project
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

from mobly import asserts
from net_tests_utils.host.python import adb_utils, apf_utils, assert_utils, multi_devices_test_base, tether_utils
from net_tests_utils.host.python.tether_utils import UpstreamType


class ApfTestBase(multi_devices_test_base.MultiDevicesTestBase):

  def setup_class(self):
    super().setup_class()

    # Check test preconditions.
    asserts.abort_class_if(
        not self.client.isAtLeastV(),
        "Do not enforce the test until V+ since chipset potential bugs are"
        " expected to be fixed on V+ releases.",
    )
    tether_utils.assume_hotspot_test_preconditions(
        self.serverDevice, self.clientDevice, UpstreamType.NONE
    )
    asserts.abort_class_if(
        not apf_utils.is_send_raw_packet_downstream_supported(
            self.serverDevice
        ),
        "NetworkStack is too old to support send raw packet, skip test.",
    )

    # Fetch device properties and storing them locally for later use.
    self.server_iface_name, client_network = (
        tether_utils.setup_hotspot_and_client_for_upstream_type(
            self.serverDevice, self.clientDevice, UpstreamType.NONE
        )
    )
    self.client_iface_name = self.client.getInterfaceNameFromNetworkHandle(
        client_network
    )
    self.server_mac_address = apf_utils.get_hardware_address(
        self.serverDevice, self.server_iface_name
    )

    # Enable doze mode to activate APF.
    adb_utils.set_doze_mode(self.clientDevice, True)

  def teardown_class(self):
    adb_utils.set_doze_mode(self.clientDevice, False)
    tether_utils.cleanup_tethering_for_upstream_type(
        self.serverDevice, UpstreamType.NONE
    )

  def send_packet_and_expect_counter_increased(
      self, packet: str, counter_name: str
  ) -> None:
    count_before_test = apf_utils.get_apf_counter(
        self.clientDevice,
        self.client_iface_name,
        counter_name,
    )
    apf_utils.send_raw_packet_downstream(
        self.serverDevice, self.server_iface_name, packet
    )

    assert_utils.expect_with_retry(
        lambda: apf_utils.get_apf_counter(
            self.clientDevice,
            self.client_iface_name,
            counter_name,
        )
        > count_before_test
    )

    # TODO: Verify the packet is not actually received.
