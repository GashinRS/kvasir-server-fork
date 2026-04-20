package templates

import (
	corev1 "k8s.io/api/core/v1"
)

#Deployment: {
	#config: #Config
	#cmName: string
	apiVersion: "apps/v1"
	kind:       "Deployment"
	metadata:   #config.metadata

	let _sourced = #config._resolvedSecretSource

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

	spec: {
		replicas: #config.replicas
		selector: matchLabels: #config.selector.labels
		template: {
			metadata: {
				labels: #config.selector.labels
				if #config.podAnnotations != _|_ {
					annotations: #config.podAnnotations
				}
			}
			spec: corev1.#PodSpec & {
				serviceAccountName: #config.metadata.name
				containers: [
					{
						name:            #config.metadata.name
						image:           #config.image.reference
						imagePullPolicy: #config.image.pullPolicy
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
						resources:       #config.resources
						securityContext: #config.securityContext
					},
				]
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
				if #config.affinity != _|_ {
					affinity: #config.affinity
				}
				if #config.tolerations != _|_ {
					tolerations: #config.tolerations
				}
				if #config.imagePullSecrets != _|_ {
					imagePullSecrets: #config.imagePullSecrets
				}
			}
		}
	}
}
