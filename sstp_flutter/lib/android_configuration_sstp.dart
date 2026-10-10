import 'package:sstp_flutter/ssl_versions.dart';

class SSTPAndroidConfiguration {
  final bool verifyHostName;
  final bool verifySSLCert;
  final bool useTrustedCert;
  final bool showDisconnectOnNotification;
  final String notificationText;
  final String sslVersion;

  /// When true, the TLS SNI is taken from [customSni] instead of the connect
  /// hostname. Some servers route TLS by SNI and reset the connection when it
  /// does not match.
  final bool useCustomSni;
  final String customSni;

  /// PPP Maximum Receive Unit advertised to the server.
  final int pppMru;

  /// PPP Maximum Transmission Unit applied to the VPN interface.
  final int pppMtu;

  /// PPP authentication protocol: "MSCHAPv2" or "PAP".
  final String pppAuthProtocol;

  /// PPP authentication timeout in seconds.
  final int pppAuthTimeout;

  final bool pppIpv4Enabled;
  final bool pppIpv6Enabled;

  /// Requests a fixed IPv4 address instead of a server-assigned one.
  final bool requestStaticIpv4;
  final String staticIpv4Address;

  /// Adds a default route through the tunnel (route all traffic).
  final bool addDefaultRoute;

  /// Also routes RFC1918 private address ranges through the tunnel.
  final bool routePrivateAddresses;

  /// Requests DNS server addresses from the peer during IPCP.
  final bool requestDns;

  final bool reconnectionEnabled;
  final int reconnectionCount;
  final int reconnectionInterval;

  SSTPAndroidConfiguration({
    this.verifyHostName = false,
    this.verifySSLCert = false,
    this.useTrustedCert = false,
    this.sslVersion = SSLVersions.DEFAULT,
    this.useCustomSni = false,
    this.customSni = "",
    this.pppMru = 1500,
    this.pppMtu = 1500,
    this.pppAuthProtocol = "MSCHAPv2",
    this.pppAuthTimeout = 5,
    this.pppIpv4Enabled = true,
    this.pppIpv6Enabled = false,
    this.requestStaticIpv4 = false,
    this.staticIpv4Address = "",
    this.addDefaultRoute = true,
    this.routePrivateAddresses = false,
    this.requestDns = true,
    this.reconnectionEnabled = false,
    this.reconnectionCount = 3,
    this.reconnectionInterval = 10,
    this.showDisconnectOnNotification = false,
    this.notificationText = "Connected",
  });
}
