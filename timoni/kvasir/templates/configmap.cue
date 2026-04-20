package templates

import (
	timoniv1 "timoni.sh/core/v1alpha1"
	"encoding/yaml"
)

#ConfigMap: timoniv1.#ImmutableConfig & {
	#config: #Config
	#Kind:   timoniv1.#ConfigMapKind
	#Meta:   #config.metadata

	let _tls = #config.kafkaTLS
	let _certPath = "\(_tls."mount-path")/\(_tls."trust-secret".key)"

	let _sourced = #config._resolvedSecretSource

	// Set of dotted Quarkus property paths that are sourced from a Secret and
	// therefore MUST be scrubbed from application.yaml. Any kind != "none"
	// means the value comes from a Secret (ref / existingSecret / managed).
	let _scrubbed = {
		for integration, fields in _sourced
		for field, src in fields
		if src.kind != "none" {
			(src.property): true
		}
	}

	// Recursive scrub: walks `applicationConfig` and drops any leaf whose
	// dotted path is in `_scrubbed`. `prefix` accumulates the "kvasir.<…>" path.
	#Scrub: {
		in: _
		prefix: string
		out: {
			for k, v in in {
				let _path = [if prefix == "" {"kvasir"}, if prefix != "" {prefix}][0] + "." + k
				if _scrubbed[_path] == _|_ {
					if (v & {[string]: _}) != _|_ {
						"\(k)": (#Scrub & {"in": v, prefix: _path}).out
					}
					if (v & {[string]: _}) == _|_ {
						"\(k)": v
					}
				}
			}
		}
	}

	let _kvasir = (#Scrub & {"in": #config.applicationConfig, prefix: ""}).out

	#Data: {
		"application.yaml": yaml.Marshal({
			kvasir: _kvasir

			if #config.quarkusConfig != _|_ || _tls.enabled {
				quarkus: {
					if #config.quarkusConfig != _|_ {
						#config.quarkusConfig
					}
					if _tls.enabled {
						tls: (_tls."config-name"): {
							"trust-store": pem: certs: _certPath
						}
					}
				}
			}

			if _tls.enabled {
				kafka: {
					security: protocol:       _tls."security-protocol"
					"tls-configuration-name": _tls."config-name"
				}
			}
		})
	}
}
