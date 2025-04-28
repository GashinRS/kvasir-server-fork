{{/*
Expand the name of the chart.
*/}}
{{- define "kvasir.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "kvasir.fullname" -}}
{{- if .Values.fullnameOverride }}
    {{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
    {{- $name := default .Chart.Name .Values.nameOverride }}
    {{- if contains $name .Release.Name }}
    {{- .Release.Name | trunc 63 | trimSuffix "-" }}
    {{- else }}
    {{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
    {{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "kvasir.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "kvasir.labels" -}}
helm.sh/chart: {{ include "kvasir.chart" . }}
{{ include "kvasir.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "kvasir.selectorLabels" -}}
app.kubernetes.io/name: {{ include "kvasir.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "kvasir.kg-change-processor.name" }}
{{- default "kg-change-processor" .Values.kgChangeProcessor.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "kvasir.kg-change-processor.fullname" }}
{{- printf "%s-%s" (include "kvasir.fullname" .) (include "kvasir.kg-change-processor.name" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "kvasir.storage-api.name" }}
{{- default "storage-api" .Values.storageApi.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "kvasir.storage-api.fullname" }}
{{- printf "%s-%s" (include "kvasir.fullname" .) (include "kvasir.storage-api.name" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "quarkus-template.clickhouse.envConfig.additional" }}
- name: KVASIR_KG_CLICKHOUSE_HOST
  value: {{ .Values.global.clickhouse.host | quote }}
- name: KVASIR_KG_CLICKHOUSE_PORT
  value: {{ .Values.global.clickhouse.port | quote }}
{{- end }}

{{- define "quarkus-template.minio.envConfig.additional" }}
- name: KVASIR_SERVICES_STORAGE_S3_HOST
  value: {{ .Values.global.minio.host  | quote }}
- name: KVASIR_SERVICES_STORAGE_S3_PORT
  value: {{ .Values.global.minio.port  | quote }}
{{- end }}
