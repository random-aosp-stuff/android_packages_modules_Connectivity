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

from unittest.mock import MagicMock, patch
from absl.testing import parameterized
from mobly import asserts
from mobly import base_test
from mobly import config_parser
from mobly.controllers.android_device_lib.adb import AdbError
from net_tests_utils.host.python.apf_utils import (
    ApfCapabilities,
    PatternNotFoundException,
    UnsupportedOperationException,
    get_apf_capabilities,
    get_apf_counter,
    get_apf_counters_from_dumpsys,
    get_ipv4_addresses,
    get_ipv6_addresses,
    get_hardware_address,
    is_send_raw_packet_downstream_supported,
    is_packet_capture_supported,
    start_capture_packets,
    stop_capture_packets,
    get_matched_packet_counts,
    send_raw_packet_downstream,
)
from net_tests_utils.host.python.assert_utils import UnexpectedBehaviorError

TEST_IFACE_NAME = "eth0"
TEST_PACKET_IN_HEX = "AABBCCDDEEFF"


class TestApfUtils(base_test.BaseTestClass, parameterized.TestCase):

  def __init__(self, configs: config_parser.TestRunConfig):
    super().__init__(configs)

  def setup_test(self):
    self.mock_ad = MagicMock()  # Mock Android device object

  @patch("net_tests_utils.host.python.adb_utils.get_dumpsys_for_service")
  def test_get_apf_counters_from_dumpsys_success(
      self, mock_get_dumpsys: MagicMock
  ) -> None:
    mock_get_dumpsys.return_value = """
IpClient.wlan0
  APF packet counters:
    COUNTER_NAME1: 123
    COUNTER_NAME2: 456
"""
    counters = get_apf_counters_from_dumpsys(self.mock_ad, "wlan0")
    asserts.assert_equal(counters, {"COUNTER_NAME1": 123, "COUNTER_NAME2": 456})

  @patch("net_tests_utils.host.python.adb_utils.get_dumpsys_for_service")
  def test_get_apf_counters_from_dumpsys_exceptions(
      self, mock_get_dumpsys: MagicMock
  ) -> None:
    test_cases = [
        "",
        "IpClient.wlan0\n",
        "IpClient.wlan0\n APF packet counters:\n",
        """
IpClient.wlan1
  APF packet counters:
    COUNTER_NAME1: 123
    COUNTER_NAME2: 456
""",
    ]

    for dumpsys_output in test_cases:
      mock_get_dumpsys.return_value = dumpsys_output
      with asserts.assert_raises(PatternNotFoundException):
        get_apf_counters_from_dumpsys(self.mock_ad, "wlan0")

  @patch("net_tests_utils.host.python.apf_utils.get_apf_counters_from_dumpsys")
  def test_get_apf_counter(self, mock_get_counters: MagicMock) -> None:
    iface = "wlan0"
    mock_get_counters.return_value = {
        "COUNTER_NAME1": 123,
        "COUNTER_NAME2": 456,
    }
    asserts.assert_equal(
        get_apf_counter(self.mock_ad, iface, "COUNTER_NAME1"), 123
    )
    # Not found
    asserts.assert_equal(
        get_apf_counter(self.mock_ad, iface, "COUNTER_NAME3"), 0
    )

  @patch("net_tests_utils.host.python.adb_utils.adb_shell")
  def test_get_hardware_address_success(
      self, mock_adb_shell: MagicMock
  ) -> None:
    mock_adb_shell.return_value = """
46: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc mq ...
 link/ether 72:05:77:82:21:e0 brd ff:ff:ff:ff:ff:ff
"""
    mac_address = get_hardware_address(self.mock_ad, "wlan0")
    asserts.assert_equal(mac_address, "72:05:77:82:21:E0")

  @patch("net_tests_utils.host.python.adb_utils.adb_shell")
  def test_get_hardware_address_not_found(
      self, mock_adb_shell: MagicMock
  ) -> None:
    mock_adb_shell.return_value = "Some output without MAC address"
    with asserts.assert_raises(PatternNotFoundException):
      get_hardware_address(self.mock_ad, "wlan0")

  @patch("net_tests_utils.host.python.adb_utils.adb_shell")
  def test_get_ipv4_addresses_success(
      self, mock_adb_shell: MagicMock
  ) -> None:
    mock_adb_shell.return_value = """
54: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UP qlen 1000
    inet 192.168.195.162/24 brd 192.168.195.255 scope global wlan0
       valid_lft forever preferred_lft forever
    inet 192.168.200.1/24 brd 192.168.200.255 scope global wlan0
       valid_lft forever preferred_lft forever
"""
    ip_addresses = get_ipv4_addresses(self.mock_ad, "wlan0")
    asserts.assert_equal(ip_addresses, ["192.168.195.162", "192.168.200.1"])

  @patch("net_tests_utils.host.python.adb_utils.adb_shell")
  def test_get_ipv4_addresses_not_found(
      self, mock_adb_shell: MagicMock
  ) -> None:
    mock_adb_shell.return_value = ""
    ip_addresses = get_ipv4_addresses(self.mock_ad, "wlan0")
    asserts.assert_equal(ip_addresses, [])

  @patch("net_tests_utils.host.python.adb_utils.adb_shell")
  def test_get_ipv6_addresses_success(
      self, mock_adb_shell: MagicMock
  ) -> None:
    mock_adb_shell.return_value = """
54: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UP qlen 1000
    inet6 fe80::10a3:5dff:fe52:de32/64 scope link
        valid_lft forever preferred_lft forever
    inet6 2001:b400:e53f:164e:9c1e:780e:d1:4658/64 scope global dynamic mngtmpaddr noprefixroute
        valid_lft 6995sec preferred_lft 6995sec
    inet6 fe80::3aff:2199:2d8e:20d1/64 scope link noprefixroute
        valid_lft forever preferred_lft forever
"""
    ip_addresses = get_ipv6_addresses(self.mock_ad, "wlan0")
    asserts.assert_equal(ip_addresses,
                         ["fe80::10a3:5dff:fe52:de32",
                          "2001:b400:e53f:164e:9c1e:780e:d1:4658",
                          "fe80::3aff:2199:2d8e:20d1"])

  @patch("net_tests_utils.host.python.adb_utils.adb_shell")
  def test_get_ipv6_address_not_found(
          self, mock_adb_shell: MagicMock
  ) -> None:
    mock_adb_shell.return_value = ""
    ip_addresses = get_ipv6_addresses(self.mock_ad, "wlan0")
    asserts.assert_equal(ip_addresses, [])

  @patch("net_tests_utils.host.python.adb_utils.adb_shell")
  def test_send_raw_packet_downstream_success(
      self, mock_adb_shell: MagicMock
  ) -> None:
    mock_adb_shell.return_value = ""  # Successful command output
    send_raw_packet_downstream(
        self.mock_ad, TEST_IFACE_NAME, TEST_PACKET_IN_HEX
    )
    mock_adb_shell.assert_called_once_with(
        self.mock_ad,
        "cmd network_stack send-raw-packet-downstream"
        f" {TEST_IFACE_NAME} {TEST_PACKET_IN_HEX}",
    )

  @patch("net_tests_utils.host.python.adb_utils.adb_shell")
  def test_send_raw_packet_downstream_failure(
      self, mock_adb_shell: MagicMock
  ) -> None:
    mock_adb_shell.return_value = (  # Unexpected command output
        "Any Unexpected Output"
    )
    with asserts.assert_raises(UnexpectedBehaviorError):
      send_raw_packet_downstream(
          self.mock_ad, TEST_IFACE_NAME, TEST_PACKET_IN_HEX
      )
    asserts.assert_true(
        is_send_raw_packet_downstream_supported(self.mock_ad),
        "Send raw packet should be supported.",
    )

  @patch("net_tests_utils.host.python.adb_utils.adb_shell")
  def test_send_raw_packet_downstream_unsupported(
      self, mock_adb_shell: MagicMock
  ) -> None:
    mock_adb_shell.side_effect = AdbError(
        cmd="", stdout="Unknown command", stderr="", ret_code=3
    )
    with asserts.assert_raises(UnsupportedOperationException):
      send_raw_packet_downstream(
          self.mock_ad, TEST_IFACE_NAME, TEST_PACKET_IN_HEX
      )
    asserts.assert_false(
        is_send_raw_packet_downstream_supported(self.mock_ad),
        "Send raw packet should not be supported.",
    )

  @patch("net_tests_utils.host.python.adb_utils.adb_shell")
  def test_start_capture_success(
          self, mock_adb_shell: MagicMock
  ) -> None:
      mock_adb_shell.return_value = "success"  # Successful command output
      start_capture_packets(
          self.mock_ad, TEST_IFACE_NAME
      )
      mock_adb_shell.assert_called_once_with(
          self.mock_ad,
          "cmd network_stack capture start"
          f" {TEST_IFACE_NAME}"
      )

  @patch("net_tests_utils.host.python.adb_utils.adb_shell")
  def test_start_capture_failure(
          self, mock_adb_shell: MagicMock
  ) -> None:
      mock_adb_shell.return_value = (  # Unexpected command output
          "Any Unexpected Output"
      )
      with asserts.assert_raises(UnexpectedBehaviorError):
          start_capture_packets(
              self.mock_ad, TEST_IFACE_NAME
          )
      asserts.assert_true(
          is_packet_capture_supported(self.mock_ad),
          "Start capturing packets should be supported.",
      )

  @patch("net_tests_utils.host.python.adb_utils.adb_shell")
  def test_start_capture_unsupported(
          self, mock_adb_shell: MagicMock
  ) -> None:
      mock_adb_shell.side_effect = AdbError(
          cmd="", stdout="Unknown command", stderr="", ret_code=3
      )
      with asserts.assert_raises(UnsupportedOperationException):
          start_capture_packets(
              self.mock_ad, TEST_IFACE_NAME
          )
      asserts.assert_false(
          is_packet_capture_supported(self.mock_ad),
          "Start capturing packets should not be supported.",
      )

  @patch("net_tests_utils.host.python.adb_utils.adb_shell")
  def test_stop_capture_success(
          self, mock_adb_shell: MagicMock
  ) -> None:
      mock_adb_shell.return_value = "success"  # Successful command output
      stop_capture_packets(
          self.mock_ad, TEST_IFACE_NAME
      )
      mock_adb_shell.assert_called_once_with(
          self.mock_ad,
          "cmd network_stack capture stop"
          f" {TEST_IFACE_NAME}"
      )

  @patch("net_tests_utils.host.python.adb_utils.adb_shell")
  def test_stop_capture_failure(
          self, mock_adb_shell: MagicMock
  ) -> None:
      mock_adb_shell.return_value = (  # Unexpected command output
          "Any Unexpected Output"
      )
      with asserts.assert_raises(UnexpectedBehaviorError):
          stop_capture_packets(
              self.mock_ad, TEST_IFACE_NAME
          )
      asserts.assert_true(
          is_packet_capture_supported(self.mock_ad),
          "Stop capturing packets should be supported.",
      )

  @patch("net_tests_utils.host.python.adb_utils.adb_shell")
  def test_stop_capture_unsupported(
          self, mock_adb_shell: MagicMock
  ) -> None:
      mock_adb_shell.side_effect = AdbError(
          cmd="", stdout="Unknown command", stderr="", ret_code=3
      )
      with asserts.assert_raises(UnsupportedOperationException):
          stop_capture_packets(
              self.mock_ad, TEST_IFACE_NAME
          )
      asserts.assert_false(
          is_packet_capture_supported(self.mock_ad),
          "Stop capturing packets should not be supported.",
      )

  @patch("net_tests_utils.host.python.adb_utils.adb_shell")
  def test_get_matched_packet_counts_success(
          self, mock_adb_shell: MagicMock
  ) -> None:
      mock_adb_shell.return_value = "10"  # Successful command output
      get_matched_packet_counts(
          self.mock_ad, TEST_IFACE_NAME, TEST_PACKET_IN_HEX
      )
      mock_adb_shell.assert_called_once_with(
          self.mock_ad,
          "cmd network_stack capture matched-packet-counts"
          f" {TEST_IFACE_NAME} {TEST_PACKET_IN_HEX}"
      )

  @patch("net_tests_utils.host.python.adb_utils.adb_shell")
  def test_get_matched_packet_counts_failure(
          self, mock_adb_shell: MagicMock
  ) -> None:
      mock_adb_shell.return_value = (  # Unexpected command output
          "Any Unexpected Output"
      )
      with asserts.assert_raises(UnexpectedBehaviorError):
          get_matched_packet_counts(
              self.mock_ad, TEST_IFACE_NAME, TEST_PACKET_IN_HEX
          )
      asserts.assert_true(
          is_packet_capture_supported(self.mock_ad),
          "Get matched packet counts should be supported.",
      )

  @patch("net_tests_utils.host.python.adb_utils.adb_shell")
  def test_get_matched_packet_counts_unsupported(
          self, mock_adb_shell: MagicMock
  ) -> None:
      mock_adb_shell.side_effect = AdbError(
          cmd="", stdout="Unknown command", stderr="", ret_code=3
      )
      with asserts.assert_raises(UnsupportedOperationException):
          get_matched_packet_counts(
              self.mock_ad, TEST_IFACE_NAME, TEST_PACKET_IN_HEX
          )
      asserts.assert_false(
          is_packet_capture_supported(self.mock_ad),
          "Get matched packet counts should not be supported.",
      )

  @parameterized.parameters(
      ("2,2048,1", ApfCapabilities(2, 2048, 1)),  # Valid input
      ("3,1024,0", ApfCapabilities(3, 1024, 0)),  # Valid input
      ("invalid,output", ApfCapabilities(0, 0, 0)),  # Invalid input
      ("", ApfCapabilities(0, 0, 0)),  # Empty input
  )
  @patch("net_tests_utils.host.python.adb_utils.adb_shell")
  def test_get_apf_capabilities(
      self, mock_output, expected_result, mock_adb_shell
  ):
    """Tests the get_apf_capabilities function with various inputs and expected results."""
    # Configure the mock adb_shell to return the specified output
    mock_adb_shell.return_value = mock_output

    # Call the function under test
    result = get_apf_capabilities(self.mock_ad, "wlan0")

    # Assert that the result matches the expected result
    asserts.assert_equal(result, expected_result)
