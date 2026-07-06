package templates

import (
	corev1 "k8s.io/api/core/v1"
	timoniv1 "timoni.sh/core/v1alpha1"
)

// Config defines the schema and defaults for the Instance values.
#Config: {
	// The kubeVersion is a required field, set at apply-time
	// via timoni.cue by querying the user's Kubernetes API.
	kubeVersion!: string
	// Using the kubeVersion you can enforce a minimum Kubernetes minor version.
	// By default, the minimum Kubernetes version is set to 1.20.
	clusterVersion: timoniv1.#SemVer & {#Version: kubeVersion, #Minimum: "1.20.0"}

	// The moduleVersion is set from the user-supplied module version.
	// This field is used for the `app.kubernetes.io/version` label.
	moduleVersion!: string

	// The Kubernetes metadata common to all resources.
	// The `metadata.name` and `metadata.namespace` fields are
	// set from the user-supplied instance name and namespace.
	metadata: timoniv1.#Metadata & {#Version: moduleVersion}

	// The labels allows adding `metadata.labels` to all resources.
	// The `app.kubernetes.io/name` and `app.kubernetes.io/version` labels
	// are automatically generated and can't be overwritten.
	metadata: labels: timoniv1.#Labels

	// The annotations allows adding `metadata.annotations` to all resources.
	metadata: annotations?: timoniv1.#Annotations

	// The selector allows adding label selectors to Deployments and Services.
	// The `app.kubernetes.io/name` label selector is automatically generated
	// from the instance name and can't be overwritten.
	selector: timoniv1.#Selector & {#Name: metadata.name}

	serviceName!: string & =~"^[a-z0-9-]+$"

	// The image allows setting the container image repository,
	// tag, digest and pull policy.
	// The default image repository and tag is set in `values.cue`.
	image: timoniv1.#Image & {
		repository: string | *"gitlab.ilabt.imec.be:4567/kvasir/kvasir-server/\(serviceName)"
		digest:     string | *""
		tag:        string | *moduleVersion
		pullPolicy: string | *"IfNotPresent"
	}

	// The resources allows setting the container resource requirements.
	// By default, the container requests 10m CPU and 32Mi memory.
	resources: timoniv1.#ResourceRequirements & {
		requests: {
			cpu:    *"10m" | timoniv1.#CPUQuantity
			memory: *"32Mi" | timoniv1.#MemoryQuantity
		}
	}

	// The number of pods replicas.
	// By default, the number of replicas is 1.
	replicas: *1 | int & >0

	// The securityContext allows setting the container security context.
	// By default, the container is denied privilege escalation.
	securityContext: corev1.#SecurityContext & {
		allowPrivilegeEscalation: *false | true
		privileged:               *false | true
		capabilities:
		{
			drop: *["ALL"] | [string]
		}
	}

	// The service allows setting the Kubernetes Service annotations and port.
	// By default, the HTTP port is 80.
	service: {
		annotations?: timoniv1.#Annotations

		port: *80 | int & >0 & <=65535
	}

	// Kvasir application configuration, marshaled into application.yaml and
	// mounted at /home/jboss/config. applicationConfig lives here (not in a
	// separate definition) so that metadata.name/namespace are concrete when
	// yaml.Marshal is evaluated — built-in marshal functions require eager concreteness.
	applicationConfig: #ApplicationConfig & {
		_name:      metadata.name
		_namespace: metadata.namespace
	}

	// Quarkus framework configuration, marshaled as top-level `quarkus:` key.
	quarkusConfig?: #QuarkusConfig

	// Pod optional settings.
	podAnnotations?: {[string]: string}
	podSecurityContext?: corev1.#PodSecurityContext
	imagePullSecrets?: [...timoniv1.#ObjectReference]
	tolerations?: [...corev1.#Toleration]
	affinity?: corev1.#Affinity
	topologySpreadConstraints?: [...corev1.#TopologySpreadConstraint]

	// Test Job disabled by default.
	test: {
		enabled: *false | bool
		image!:  timoniv1.#Image
	}

	// Kafka TLS trust configuration. Disabled by default.
	// When enabled, mounts a CA certificate from a Kubernetes Secret and configures
	// the Quarkus TLS Registry for server-only TLS (not mTLS).
	kafkaTLS: #KafkaTLS

	// Sensitive credential sourcing. Set `secretName` at integration level,
	// or per-field `secretName`/`key` overrides, or `value` for dev/test.
	secrets: #Secrets

	_resolvedSecretSource: #ResolveSecrets & {#secrets: secrets, #instanceName: metadata.name}

	secrets: #SecretsSchema & {#appConfig: applicationConfig} & #Secrets

	_secretWarnings: [
		for integration, binding in secrets
		if binding.secretName == _|_ && len([for k, v in binding.fields if v.value != _|_ || v.secretName != _|_ {v}]) == 0 {
			"WARNING: secrets.\(integration) has no secretName and no field values configured."
		},
	]

	// startupProbe gives the container a generous bootstrap budget before the
	// liveness probe starts firing. Per Kubernetes guidance the default mirrors
	// the liveness endpoint (/q/health/live) so that once startup completes the
	// liveness probe takes over checking the same signal — see
	// https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/#define-startup-probes
	//
	// Default budget: 5 minutes (10s × 30 attempts). Tune `failureThreshold` and
	// `periodSeconds` for environments with slower bootstrap (e.g. cold-start
	// Keycloak/OpenFGA dependencies). Override `path` to /q/health/ready if you
	// want the pod to remain "starting" until Kvasir's Initializer completes
	// (Keycloak realm, OpenFGA model, Kafka topics, pod bootstrap).
	startupProbe: {
		enabled:             *true | bool
		path:                *"/q/health/live" | string
		periodSeconds:       *10 | int & >0
		failureThreshold:    *30 | int & >0
		timeoutSeconds:      *1 | int & >0
		initialDelaySeconds: *0 | int & >=0
	}

	// Deployment mode: "monolith" (default) or "microservices".
	// - monolith: Single deployment running all service code (current behavior)
	// - microservices: Separate deployment per service, shared ConfigMap/Secrets
	deploymentMode: *"monolith" | "microservices"

	// Per-service configuration overrides. Only applicable when deploymentMode is "microservices".
	// Services not listed here use defaults from #ServiceCatalog.
	// Set `enabled: false` to skip deploying a service.
	services: #ServicesConfig

	// Init service configuration. Runs as a Kubernetes Job before deployments.
	// Only applicable when deploymentMode is "microservices".
	init: #InitConfig

	// Traefik IngressRoute configuration. Disabled by default.
	ingress: #IngressConfig
}

#ResolveSecrets: {
	#secrets:      #Secrets
	#instanceName: string

	out: {
		for integration, binding in #secrets {
			"\(integration)": {
				for _fieldKey, meta in binding.fields {
					let _managedSecretName = "\(#instanceName)-\(integration)-secret"
					let _effectiveKey = [if meta.key != _|_ {meta.key}, _fieldKey][0]
					let _effectiveSecretName = [if meta.secretName != _|_ {meta.secretName}, if binding.secretName != _|_ {binding.secretName}, ""][0]

					"\(_fieldKey)": {
						env:  meta.env
						kind: *"none" | "existing" | "managed"

						if meta.value != _|_ {
							kind:       "managed"
							secretName: _managedSecretName
							secretKey:  _effectiveKey
							value:      meta.value & string
						}

						if meta.value == _|_ && _effectiveSecretName != "" {
							kind:       "existing"
							secretName: _effectiveSecretName
							secretKey:  _effectiveKey
						}
					}
				}
			}
		}
	}
}

#IngressConfig: {
	enabled:    *false | bool
	entryPoint: *"web" | string
	host?:      string
}

// Per-service configuration schema
#ServiceConfig: {
	// Enable/disable this service. Defaults from catalog.
	enabled?: bool

	// Number of replicas. Defaults to 1.
	replicas?: int & >0

	// Resource requirements override.
	resources?: timoniv1.#ResourceRequirements

	// Image override (tag/digest). Repository derived from serviceName.
	image?: {
		tag?:        string
		digest?:     string
		pullPolicy?: string
	}

	// HPA configuration. Disabled by default.
	autoscaling?: {
		enabled:     *false | bool
		minReplicas: *1 | int & >0
		maxReplicas: *10 | int & >0
		// CPU threshold as percentage (e.g., 80 = 80%)
		targetCPUUtilizationPercentage: *80 | int & >0 & <=100
	}

	// Pod annotations override.
	podAnnotations?: {[string]: string}

	// Affinity override.
	affinity?: corev1.#Affinity

	// Tolerations override.
	tolerations?: [...corev1.#Toleration]
}

// Services configuration map. Keys must match service names from #ServiceCatalog.
#ServicesConfig: {
	// HTTP services (require Ingress routing)
	"ui-service"?:         #ServiceConfig
	"solid-api"?:          #ServiceConfig
	"storage-api"?:        #ServiceConfig
	"kg-stream-api"?:      #ServiceConfig
	"kg-query-api"?:       #ServiceConfig
	"kg-changes-api"?:     #ServiceConfig
	"pod-management-api"?: #ServiceConfig

	// Backend services (Kafka consumers, no HTTP)
	"kg-change-processor"?: #ServiceConfig
	"simple-rdf-ingester"?: #ServiceConfig
}

// Init service (bootstrap job) configuration
#InitConfig: {
	// Enable the init job. Required for fresh cluster setup.
	enabled: *true | bool

	// Run bootstrap and exit. Set to true for one-shot initialization.
	exitAfterSetup: *true | bool

	// Job restart policy.
	restartPolicy: *"OnFailure" | "Never"

	// Backoff limit for failed job attempts.
	backoffLimit: *3 | int & >=0

	// TTL for completed jobs (seconds). 0 = never delete.
	ttlSecondsAfterFinished?: int & >=0

	// Resource requirements for init container.
	resources?: timoniv1.#ResourceRequirements

	// Image override.
	image?: {
		tag?:        string
		digest?:     string
		pullPolicy?: string
	}
}

// Instance takes the config values and outputs the Kubernetes objects.
#Instance: {
	config: #Config

	objects: {
		sa: #ServiceAccount & {#config: config}
		cm: #ConfigMap & {#config: config}

		for _svcName, _meta in #ServiceCatalog
		if _meta.kind != "job" && ((config.deploymentMode == "monolith" && _svcName == "monolith") || (config.deploymentMode == "microservices" && _svcName != "monolith" && _svcName != "init-service")) {
			let _objName = [if _svcName == "monolith" {"deploy"}, "deploy-\(_svcName)"][0]
			(_objName): (#ServiceDeployment & {
				#config:      config
				#serviceName: _svcName
				#cmName:      cm.metadata.name
			}).out

			if _meta.kind == "http" {
				let _svcObjName = [if _svcName == "monolith" {"svc"}, "svc-\(_svcName)"][0]
				(_svcObjName): (#ServiceService & {
					#config:      config
					#serviceName: _svcName
				}).out
			}
		}

		if config.deploymentMode == "microservices" && config.init.enabled {
			"job-init": (#InitJob & {
				#config: config
				#cmName: cm.metadata.name
			}).out
		}

		for integration, binding in config.secrets {
			let _hasValues = [for k, v in binding.fields if (v.value & string) != _|_ {v}]

			if len(_hasValues) > 0 {
				"secret-\(integration)": (#ManagedSecret & {
					#config:      config
					#integration: integration
					#fields:      binding.fields
				}).out
			}
		}

		if config.ingress.enabled {
			ingress: #IngressRoute & {
				#config:     config
				#entryPoint: config.ingress.entryPoint
				if config.ingress.host != _|_ {
					#host: config.ingress.host
				}
			}
			if config.deploymentMode == "microservices" {
				"ui-trailing-slash-middleware": #UiTrailingSlashMiddleware & {#config: config}
			}
		}
	}

	tests: {
		"test-svc": #TestJob & {#config: config}
	}

	// Warnings about secret configuration (exposed during timoni build)
	if len(config._secretWarnings) > 0 {
		warnings: config._secretWarnings
	}
}
