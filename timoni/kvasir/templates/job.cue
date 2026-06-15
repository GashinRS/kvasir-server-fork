package templates

import (
	"encoding/yaml"
	"list"
	"uuid"

	corev1 "k8s.io/api/core/v1"
	batchv1 "k8s.io/api/batch/v1"
	timoniv1 "timoni.sh/core/v1alpha1"
)

#InitJob: {
	#config: #Config
	#cmName: string

	let _initConfig = #config.init
	let _serviceMeta = #ServiceCatalog["init-service"]
	let _imageRef = "gitlab.ilabt.imec.be:4567/kvasir/kvasir-server/\(_serviceMeta.imageSuffix):\(#config.image.tag)"

	let _sourced = #config._resolvedSecretSource
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

	let _resources = *_initConfig.resources | #config.resources

	if _initConfig.enabled {
		out: batchv1.#Job & {
			apiVersion: "batch/v1"
			kind:       "Job"
			metadata: {
				name:      "\(#config.metadata.name)-init"
				namespace: #config.metadata.namespace
				labels:    #config.metadata.labels & {
					"app.kubernetes.io/component": "init"
				}
				annotations: timoniv1.Action.Force
				if #config.metadata.annotations != _|_ {
					annotations: #config.metadata.annotations
				}
			}
			spec: {
				backoffLimit: _initConfig.backoffLimit
				if _initConfig.ttlSecondsAfterFinished != _|_ {
					ttlSecondsAfterFinished: _initConfig.ttlSecondsAfterFinished
				}
				template: {
					let _checksum = uuid.SHA1(uuid.ns.DNS, yaml.Marshal(#config))
					metadata: {
						labels: {
							"app.kubernetes.io/name":      #config.metadata.name
							"app.kubernetes.io/component": "init"
						}
						annotations: "timoni.sh/checksum": "\(_checksum)"
						if #config.podAnnotations != _|_ {
							annotations: #config.podAnnotations
						}
					}
					spec: corev1.#PodSpec & {
						restartPolicy:      _initConfig.restartPolicy
						serviceAccountName: #config.metadata.name
						containers: [{
							name:            "init"
							image:           _imageRef
							imagePullPolicy: #config.image.pullPolicy
							ports: [{
								name:          "management"
								containerPort: 9100
								protocol:      "TCP"
							}]
							env: list.Concat([_envFromSecrets, [
								if _initConfig.exitAfterSetup {
									{
										name:  "KVASIR_BOOTSTRAP_EXIT_AFTER_SETUP"
										value: "true"
									}
								},
							]])
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
	}
}

#TestJob: batchv1.#Job & {
	#config:    #Config
	apiVersion: "batch/v1"
	kind:       "Job"
	metadata: timoniv1.#MetaComponent & {
		#Meta:      #config.metadata
		#Component: "test"
	}
	metadata: annotations: timoniv1.Action.Force
	spec: batchv1.#JobSpec & {
		template: corev1.#PodTemplateSpec & {
			let _checksum = uuid.SHA1(uuid.ns.DNS, yaml.Marshal(#config))
			metadata: annotations: "timoni.sh/checksum": "\(_checksum)"
			spec: {
				containers: [{
					name:            "curl"
					image:           #config.test.image.reference
					imagePullPolicy: #config.test.image.pullPolicy
					command: [
						"curl",
						"-v",
						"-m",
						"5",
						"\(#config.metadata.name):\(#config.service.port)",
					]
				}]
				restartPolicy: "Never"
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
		backoffLimit: 1
	}
}
