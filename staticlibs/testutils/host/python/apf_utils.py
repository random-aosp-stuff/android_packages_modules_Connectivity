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

from dataclasses import dataclass
import re
from mobly import asserts
from mobly.controllers import android_device
from mobly.controllers.android_device_lib.adb import AdbError
from net_tests_utils.host.python import adb_utils, assert_utils


class PatternNotFoundException(Exception):
  """Raised when the given pattern cannot be found."""


class UnsupportedOperationException(Exception):
  pass


def get_apf_counter(
    ad: android_device.AndroidDevice, iface: str, counter_name: str
) -> int:
  counters = get_apf_counters_from_dumpsys(ad, iface)
  return counters.get(counter_name, 0)


def get_apf_counters_from_dumpsys(
    ad: android_device.AndroidDevice, iface_name: str
) -> dict:
  dumpsys = adb_utils.get_dumpsys_for_service(ad, "network_stack")

  # Extract IpClient section of the specified interface.
  # This takes inputs like:
  # IpClient.wlan0
  #   ...
  # IpClient.wlan1
  #   ...
  iface_pattern = re.compile(
      r"^IpClient\." + iface_name + r"\n" + r"((^\s.*\n)+)", re.MULTILINE
  )
  iface_result = iface_pattern.search(dumpsys)
  if iface_result is None:
    raise PatternNotFoundException("Cannot find IpClient for " + iface_name)

  # Extract APF counters section from IpClient section, which looks like:
  #     APF packet counters:
  #       COUNTER_NAME: VALUE
  #       ....
  apf_pattern = re.compile(
      r"APF packet counters:.*\n.(\s+[A-Z_0-9]+: \d+\n)+", re.MULTILINE
  )
  apf_result = apf_pattern.search(iface_result.group(0))
  if apf_result is None:
    raise PatternNotFoundException(
        "Cannot find APF counters in text: " + iface_result.group(0)
    )

  # Extract key-value pairs from APF counters section into a list of tuples,
  # e.g. [('COUNTER1', '1'), ('COUNTER2', '2')].
  counter_pattern = re.compile(r"(?P<name>[A-Z_0-9]+): (?P<value>\d+)")
  counter_result = counter_pattern.findall(apf_result.group(0))
  if counter_result is None:
    raise PatternNotFoundException(
        "Cannot extract APF counters in text: " + apf_result.group(0)
    )

  # Convert into a dict.
  result = {}
  for key, value_str in counter_result:
    result[key] = int(value_str)

  ad.log.debug("Getting apf counters: " + str(result))
  return result


def get_hardware_address(
    ad: android_device.AndroidDevice, iface_name: str
) -> str:
  """Retrieves the hardware (MAC) address for a given network interface.

  Returns:
      The hex representative of the MAC address in uppercase.
      E.g. 12:34:56:78:90:AB

  Raises:
      PatternNotFoundException: If the MAC address is not found in the command
      output.
  """

  # Run the "ip link" command and get its output.
  ip_link_output = adb_utils.adb_shell(ad, f"ip link show {iface_name}")

  # Regular expression to extract the MAC address.
  # Parse hardware address from ip link output like below:
  # 46: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc mq ...
  #    link/ether 72:05:77:82:21:e0 brd ff:ff:ff:ff:ff:ff
  pattern = r"link/ether (([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2})"
  match = re.search(pattern, ip_link_output)

  if match:
    return match.group(1).upper()  # Extract the MAC address string.
  else:
    raise PatternNotFoundException(
        "Cannot get hardware address for " + iface_name
    )


def is_send_raw_packet_downstream_supported(
    ad: android_device.AndroidDevice,
) -> bool:
  try:
    # Invoke the shell command with empty argument and see how NetworkStack respond.
    # If supported, an IllegalArgumentException with help page will be printed.
    send_raw_packet_downstream(ad, "", "")
  except assert_utils.UnexpectedBehaviorError:
    return True
  except UnsupportedOperationException:
    return False


def send_raw_packet_downstream(
    ad: android_device.AndroidDevice,
    iface_name: str,
    packet_in_hex: str,
) -> None:
  """Sends a raw packet over the specified downstream interface.

  This function constructs and sends a raw packet using the
  `send-raw-packet-downstream`
  command provided by NetworkStack process. It's primarily intended for testing
  purposes.

  Args:
      ad: The AndroidDevice object representing the connected device.
      iface_name: The name of the network interface to use (e.g., "wlan0",
        "eth0").
      packet_in_hex: The raw packet data starting from L2 header encoded in
        hexadecimal string format.

  Raises:
      UnsupportedOperationException: If the NetworkStack doesn't support
        the `send-raw-packet` command.
      UnexpectedBehaviorException: If the command execution produces unexpected
        output other than an empty response or "Unknown command".

  Important Considerations:
      Security: This method only works on tethering downstream interfaces due
        to security restrictions.
      Packet Format: The `packet_in_hex` must be a valid hexadecimal
        representation of a packet starting from L2 header.
  """

  cmd = (
      "cmd network_stack send-raw-packet-downstream"
      f" {iface_name} {packet_in_hex}"
  )

  # Expect no output or Unknown command if NetworkStack is too old. Throw otherwise.
  try:
    output = adb_utils.adb_shell(ad, cmd)
  except AdbError as e:
    output = str(e.stdout)
  if output:
    if "Unknown command" in output:
      raise UnsupportedOperationException(
          "send-raw-packet-downstream command is not supported."
      )
    raise assert_utils.UnexpectedBehaviorError(
        f"Got unexpected output: {output} for command: {cmd}."
    )


@dataclass
class ApfCapabilities:
  """APF program support capabilities.

  See android.net.apf.ApfCapabilities.

  Attributes:
      apf_version_supported (int): Version of APF instruction set supported for
        packet filtering. 0 indicates no support for packet filtering using APF
        programs.
      apf_ram_size (int): Size of APF ram.
      apf_packet_format (int): Format of packets passed to APF filter. Should be
        one of ARPHRD_*
  """

  apf_version_supported: int
  apf_ram_size: int
  apf_packet_format: int

  def __init__(
      self,
      apf_version_supported: int,
      apf_ram_size: int,
      apf_packet_format: int,
  ):
    self.apf_version_supported = apf_version_supported
    self.apf_ram_size = apf_ram_size
    self.apf_packet_format = apf_packet_format

  def __str__(self):
    """Returns a user-friendly string representation of the APF capabilities."""
    return (
        f"APF Version: {self.apf_version_supported}\n"
        f"Ram Size: {self.apf_ram_size} bytes\n"
        f"Packet Format: {self.apf_packet_format}"
    )


def get_apf_capabilities(
    ad: android_device.AndroidDevice, iface_name: str
) -> ApfCapabilities:
  output = adb_utils.adb_shell(
      ad, f"cmd network_stack apf {iface_name} capabilities"
  )
  try:
    values = [int(value_str) for value_str in output.split(",")]
  except ValueError:
    return ApfCapabilities(0, 0, 0)  # Conversion to integer failed
  return ApfCapabilities(values[0], values[1], values[2])


def assume_apf_version_support_at_least(
    ad: android_device.AndroidDevice, iface_name: str, expected_version: int
) -> None:
  caps = get_apf_capabilities(ad, iface_name)
  asserts.abort_class_if(
      caps.apf_version_supported < expected_version,
      f"Supported apf version {caps.apf_version_supported} < expected version"
      f" {expected_version}",
  )
