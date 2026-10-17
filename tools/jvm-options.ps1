# Shared by source runs, memory measurements, and the portable launcher.
# Keep the heap ceiling for larger histories; reduce the idle JVM footprint.
function Get-TimerJvmOptions([string]$Xmx = "256m") {
  @("-Xmx$Xmx", "-Xms16m", "-XX:+UseSerialGC", "-XX:CICompilerCount=2",
    "-XX:ReservedCodeCacheSize=64m", "-Dfile.encoding=UTF-8",
    "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8")
}
