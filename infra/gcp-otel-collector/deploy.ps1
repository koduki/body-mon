param(
    [string]$ProjectId = "health-support-ai",
    [string]$Region = "asia-northeast1",
    [string]$ServiceName = "body-mon-otel-collector",
    [string]$SecretName = "body-mon-otel-ingest-token"
)

$ErrorActionPreference = "Stop"
$SaName = "body-mon-otel-collector"
$SaEmail = "$SaName@$ProjectId.iam.gserviceaccount.com"

Write-Host "1. Enabling required Google Cloud APIs in $ProjectId..."
gcloud.cmd services enable `
    run.googleapis.com `
    cloudbuild.googleapis.com `
    artifactregistry.googleapis.com `
    secretmanager.googleapis.com `
    telemetry.googleapis.com `
    cloudtrace.googleapis.com `
    logging.googleapis.com `
    monitoring.googleapis.com `
    --project=$ProjectId --quiet

Write-Host "2. Ensuring Service Account $SaEmail exists..."
$existingSa = gcloud.cmd iam service-accounts list --project=$ProjectId --filter="email:$SaEmail" --format="value(email)" --quiet
if (-not $existingSa) {
    gcloud.cmd iam service-accounts create $SaName `
        --display-name="body-mon OpenTelemetry Collector" `
        --project=$ProjectId --quiet
    Start-Sleep -Seconds 5
}

$roles = @(
    "roles/telemetry.tracesWriter",
    "roles/cloudtrace.agent",
    "roles/logging.logWriter",
    "roles/monitoring.metricWriter"
)
foreach ($role in $roles) {
    gcloud.cmd projects add-iam-policy-binding $ProjectId `
        --member="serviceAccount:$SaEmail" `
        --role=$role `
        --condition=None `
        --quiet | Out-Null
}

Write-Host "3. Ensuring Secret Manager secret $SecretName exists..."
$existingSecret = gcloud.cmd secrets list --project=$ProjectId --format="value(name)" --quiet | Where-Object { $_ -match "(^|/)$SecretName$" }
if (-not $existingSecret) {
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $token = "bmon_" + ([System.BitConverter]::ToString($bytes).Replace("-", "").ToLowerInvariant())
    $tmpFile = [System.IO.Path]::GetTempFileName()
    try {
        [System.IO.File]::WriteAllText($tmpFile, $token)
        gcloud.cmd secrets create $SecretName `
            --replication-policy="automatic" `
            --data-file=$tmpFile `
            --project=$ProjectId --quiet
    } finally {
        if (Test-Path $tmpFile) { del $tmpFile }
    }
}

gcloud.cmd secrets add-iam-policy-binding $SecretName `
    --member="serviceAccount:$SaEmail" `
    --role="roles/secretmanager.secretAccessor" `
    --project=$ProjectId --quiet | Out-Null

Write-Host "4. Deploying Cloud Run service $ServiceName in $Region..."
gcloud.cmd run deploy $ServiceName `
    --source="$PSScriptRoot" `
    --region=$Region `
    --project=$ProjectId `
    --service-account=$SaEmail `
    --allow-unauthenticated `
    --min-instances=0 `
    --max-instances=1 `
    --memory=256Mi `
    --cpu=1 `
    --set-env-vars="GCP_PROJECT_ID=$ProjectId" `
    --set-secrets="OTEL_INGEST_TOKEN=${SecretName}:latest" `
    --quiet

$url = gcloud.cmd run services describe $ServiceName --region=$Region --project=$ProjectId --format="value(status.url)" --quiet
Write-Host "Deployed OTLP Collector endpoint: $url/v1/traces"
