// Action: Show IDE version and build info
// Usage: intellij-cli action version

import com.intellij.openapi.application.ApplicationInfo

val appInfo = ApplicationInfo.getInstance()

println("IntelliJ IDEA Information")
println("=" .repeat(40))
println("Name:         ${appInfo.fullApplicationName}")
println("Version:      ${appInfo.fullVersion}")
println("Build:        ${appInfo.build}")
println("Vendor:       ${appInfo.companyName}")
println("API Version:  ${appInfo.apiVersion}")
println("Major:        ${appInfo.majorVersion}")
println("Minor:        ${appInfo.minorVersion}")
