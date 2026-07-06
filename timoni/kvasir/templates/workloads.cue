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

	let _isMonolith = #serviceName == "monolith"
	let _hasHttp = #serviceMeta.kind == "http"
	let _enabled = *#svcConfig.enabled | true
	let _replicas = *#svcConfig.replicas | *#config.replicas | 1
	let _resources = *#svcConfig.resources | #config.resources

	let _workloadName = [if _isMonolith {#config.metadata.name}, "\(#config.metadata.name)-\(#serviceName)"][0]
	let _containerName = [if _isMonolith {#config.metadata.name}, #serviceName][0]
	let _imageRef = [if _isMonolith {#config.image.reference}, "gitlab.ilabt.imec.be:4567/kvasir/kvasir-server/\(#serviceMeta.imageSuffix):\(#config.image.tag)"][0]

	let _sourced = #config._resolvedSecretSource.out

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

	let _selectorLabels = {
		"app.kubernetes.io/name": #config.metadata.name
		if !_isMonolith {
			"app.kubernetes.io/component": #serviceName
		}
	}

	if _enabled {
		out: appsv1.#Deployment & {
			apiVersion: "apps/v1"
			kind:       "Deployment"
			metadata: {
				name:      _workloadName
				namespace: #config.metadata.namespace
				labels: #config.metadata.labels & {
					if !_isMonolith {
						"app.kubernetes.io/component": #serviceName
					}
				}
				if #config.metadata.annotations != _|_ {
					annotations: #config.metadata.annotations
				}
			}
			spec: {
				replicas: _replicas
				selector: matchLabels: _selectorLabels
				template: {
					metadata: {
						labels: _selectorLabels
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
							name:            _containerName
							image:           _imageRef
							imagePullPolicy: #config.image.pullPolicy
							if _hasHttp {
								ports: [
									{name: "http", containerPort: 8080, protocol: "TCP"},
									{name: "management", containerPort: 9100, protocol: "TCP"},
								]
							}
							if !_hasHttp {
								ports: [{name: "management", containerPort: 9100, protocol: "TCP"}]
							}

							if len(_envFromSecrets) > 0 {
								env: _envFromSecrets
							}

							if #config.startupProbe.enabled {
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
									httpGet: {path: "/q/health/live", port: "management"}
									initialDelaySeconds: 10
									periodSeconds:       30
								}
								readinessProbe: {
									httpGet: {path: "/q/health/ready", port: "management"}
									initialDelaySeconds: 10
									periodSeconds:       10
								}
							}
							if !_hasHttp {
								livenessProbe: {
									httpGet: {path: "/q/health/live", port: "management"}
									initialDelaySeconds: 30
									periodSeconds:       60
								}
							}

							volumeMounts: [
								{name: "config", mountPath: "/home/jboss/config/", readOnly: true},
								if #config.kafkaTLS.enabled {
									{name: "kafka-tls", mountPath: #config.kafkaTLS."mount-path", readOnly: true}
								},
							]
							resources:       _resources
							securityContext: #config.securityContext
						}]
						volumes: [
							{
								name: "config"
								configMap: corev1.#ConfigMapVolumeSource & {name: #cmName}
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

	let _isMonolith = #serviceName == "monolith"
	let _hasHttp = #serviceMeta.kind == "http"
	let _enabled = *#svcConfig.enabled | true
	let _serviceName = [if _isMonolith {#config.metadata.name}, "\(#config.metadata.name)-\(#serviceName)"][0]

	let _selectorLabels = {
		"app.kubernetes.io/name": #config.metadata.name
		if !_isMonolith {
			"app.kubernetes.io/component": #serviceName
		}
	}

	if _enabled && _hasHttp {
		out: corev1.#Service & {
			apiVersion: "v1"
			kind:       "Service"
			metadata: {
				name:      _serviceName
				namespace: #config.metadata.namespace
				labels: #config.metadata.labels & {
					if !_isMonolith {
						"app.kubernetes.io/component": #serviceName
					}
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
				selector: _selectorLabels
			}
		}
	}
}
