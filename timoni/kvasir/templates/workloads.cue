package templates

import (
	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
)

#ServiceDeployment: {
	#config:      #Config
	#serviceName: string
	#serviceMeta: #ServiceCatalog[#serviceName]
	#svcConfig: *#config.services[#serviceName] | {}
	#cmName: string

	let _hasHttp = #serviceMeta.kind == "http"
	let _enabled = *#svcConfig.enabled | true
	let _replicas = *#svcConfig.replicas | *#config.replicas | 1
	let _resources = *#svcConfig.resources | #config.resources
	let _imageRef = "gitlab.ilabt.imec.be:4567/kvasir/kvasir-server/\(#serviceMeta.imageSuffix):\(#config.image.tag)"

	let _sourced = #config._resolvedSecretSource.out

	// Derive the env var list from the resolved source map. Each (integration,
	// field) with kind != "none" becomes one env entry pointing to its Secret.
	let _envFromSecrets = [
		for integration, fields in _sourced
		for field, src in fields
		if src.kind != "none" {
			name: src.env
			valueFrom: secretKeyRef: {
				name: src.secretName
				key:  src.secretKey
			}
		},
	]

	if _enabled {
		out: appsv1.#Deployment & {
			apiVersion: "apps/v1"
			kind:       "Deployment"
			metadata: {
				name:      "\(#config.metadata.name)-\(#serviceName)"
				namespace: #config.metadata.namespace
				labels: #config.metadata.labels & {
					"app.kubernetes.io/component": #serviceName
				}
				if #config.metadata.annotations != _|_ {
					annotations: #config.metadata.annotations
				}
			}
			spec: {
				replicas: _replicas
				selector: matchLabels: {
					"app.kubernetes.io/name":      #config.metadata.name
					"app.kubernetes.io/component": #serviceName
				}
				template: {
					metadata: {
						labels: {
							"app.kubernetes.io/name":      #config.metadata.name
							"app.kubernetes.io/component": #serviceName
						}
						if #svcConfig.podAnnotations != _|_ {
							annotations: #svcConfig.podAnnotations
						}
						if #svcConfig.podAnnotations == _|_ && #config.podAnnotations != _|_ {
							annotations: #config.podAnnotations
						}
					}
					spec: corev1.#PodSpec & {
						serviceAccountName: #config.metadata.name
						containers: [{
							name:            #serviceName
							image:           _imageRef
							imagePullPolicy: #config.image.pullPolicy
							if _hasHttp {
								ports: [
									{
										name:          "http"
										containerPort: 8080
										protocol:      "TCP"
									},
									{
										name:          "management"
										containerPort: 9100
										protocol:      "TCP"
									},
								]
							}
							if !_hasHttp {
								ports: [{
									name:          "management"
									containerPort: 9100
									protocol:      "TCP"
								}]
							}
							if !_hasHttp && #config.startupProbe.enabled {
								startupProbe: {
									httpGet: {
										path: #config.startupProbe.path
										port: "management"
									}
									initialDelaySeconds: #config.startupProbe.initialDelaySeconds
									periodSeconds:       #config.startupProbe.periodSeconds
									failureThreshold:    #config.startupProbe.failureThreshold
									timeoutSeconds:      #config.startupProbe.timeoutSeconds
								}
							}
							if !_hasHttp {
								livenessProbe: {
									httpGet: {
										path: "/q/health/live"
										port: "management"
									}
									initialDelaySeconds: 30
									periodSeconds:       60
								}
							}
							if len(_envFromSecrets) > 0 {
								env: _envFromSecrets
							}
							if _hasHttp && #config.startupProbe.enabled {
								startupProbe: {
									httpGet: {
										path: #config.startupProbe.path
										port: "management"
									}
									initialDelaySeconds: #config.startupProbe.initialDelaySeconds
									periodSeconds:       #config.startupProbe.periodSeconds
									failureThreshold:    #config.startupProbe.failureThreshold
									timeoutSeconds:      #config.startupProbe.timeoutSeconds
								}
							}
							if _hasHttp {
								livenessProbe: {
									httpGet: {
										path: "/q/health/live"
										port: "management"
									}
									initialDelaySeconds: 10
									periodSeconds:       30
								}
								readinessProbe: {
									httpGet: {
										path: "/q/health/ready"
										port: "management"
									}
									initialDelaySeconds: 10
									periodSeconds:       10
								}
							}
							volumeMounts: [
								{
									name:      "config"
									mountPath: "/home/jboss/config/"
									readOnly:  true
								},
								if #config.kafkaTLS.enabled {
									{
										name:      "kafka-tls"
										mountPath: #config.kafkaTLS."mount-path"
										readOnly:  true
									}
								},
							]
							resources:       _resources
							securityContext: #config.securityContext
						}]
						volumes: [
							{
								name: "config"
								configMap: corev1.#ConfigMapVolumeSource & {
									name: #cmName
								}
							},
							if #config.kafkaTLS.enabled {
								{
									name: "kafka-tls"
									secret: corev1.#SecretVolumeSource & {
										secretName: #config.kafkaTLS."trust-secret".name
										items: [{
											key:  #config.kafkaTLS."trust-secret".key
											path: #config.kafkaTLS."trust-secret".key
										}]
									}
								}
							},
						]
						if #config.podSecurityContext != _|_ {
							securityContext: #config.podSecurityContext
						}
						if #config.topologySpreadConstraints != _|_ {
							topologySpreadConstraints: #config.topologySpreadConstraints
						}
						let _affinity = *#svcConfig.affinity | *#config.affinity | null
						if _affinity != null {
							affinity: _affinity
						}
						let _tolerations = *#svcConfig.tolerations | *#config.tolerations | null
						if _tolerations != null {
							tolerations: _tolerations
						}
						if #config.imagePullSecrets != _|_ {
							imagePullSecrets: #config.imagePullSecrets
						}
					}
				}
			}
		}
	}
}

#ServiceService: {
	#config:      #Config
	#serviceName: string
	#serviceMeta: #ServiceCatalog[#serviceName]
	#svcConfig: *#config.services[#serviceName] | {}

	let _hasHttp = #serviceMeta.kind == "http"
	let _enabled = *#svcConfig.enabled | true

	if _enabled && _hasHttp {
		out: corev1.#Service & {
			apiVersion: "v1"
			kind:       "Service"
			metadata: {
				name:      "\(#config.metadata.name)-\(#serviceName)"
				namespace: #config.metadata.namespace
				labels: #config.metadata.labels & {
					"app.kubernetes.io/component": #serviceName
				}
				if #config.service.annotations != _|_ {
					annotations: #config.service.annotations
				}
			}
			spec: {
				type: "ClusterIP"
				ports: [{
					name:       "http"
					port:       #config.service.port
					targetPort: "http"
					protocol:   "TCP"
				}]
				selector: {
					"app.kubernetes.io/name":      #config.metadata.name
					"app.kubernetes.io/component": #serviceName
				}
			}
		}
	}
}
