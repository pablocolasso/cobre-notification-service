# Publishes key|json lines to the platform events topic through the Kafka container of docker compose.
# Usage: .\scripts\publish-events.ps1 [-File demo\platform-events.jsonl]
param(
    [string]$File = (Join-Path $PSScriptRoot "..\demo\platform-events.jsonl"),
    [string]$Topic = "platform.events.v1"
)

$ErrorActionPreference = "Stop"

$lines = Get-Content -Path $File | Where-Object { $_.Trim() -ne "" }
$lines | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh `
    --bootstrap-server kafka:29092 `
    --topic $Topic `
    --property parse.key=true `
    --property "key.separator=|"

if ($LASTEXITCODE -ne 0) {
    throw "kafka-console-producer failed with exit code $LASTEXITCODE"
}
Write-Host "Published $($lines.Count) events to $Topic"
