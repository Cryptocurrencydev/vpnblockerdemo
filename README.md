# VPNBlockerDemo

A lightweight Android VPN proof-of-concept app that intercepts DNS queries using `VpnService` to demonstrate domain-based blocking on-device.

## ✨ Features

- Blocks specified hardcoded domains via DNS interception  
- Allows safe DNS passthrough to Google DNS (8.8.8.8)  
- Simple start/stop logic managed by the `VpnService` lifecycle  
- Real-time DNS query logging via `Logcat`

## 🔒 Use Case

This is a **device-local prototype** meant to showcase domain blocking for DNS queries.  
To enable blocking for other devices on a network, a custom DNS server or router-level implementation would be required — to be scoped in future milestones.

## 📦 Blocked Domains (Example)

Currently hardcoded in `DnsVpnService.kt`:

```kotlin
private val blockedDomains = listOf("www.xvideos.com")
```

