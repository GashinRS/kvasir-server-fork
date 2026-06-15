package templates

#UiTrailingSlashMiddleware: {
	#config: #Config

	apiVersion: "traefik.io/v1alpha1"
	kind:       "Middleware"
	metadata: {
		name:      "\(#config.metadata.name)-ui-trailing-slash"
		namespace: #config.metadata.namespace
		labels:    #config.metadata.labels
	}
	spec: redirectRegex: {
		regex:       "^(https?://[^/]+/_ui)$"
		replacement: "${1}/"
		permanent:   false
	}
}

#IngressRoute: {
	#config:     #Config
	#entryPoint: *"web" | string
	#host?:      string

	apiVersion: "traefik.io/v1alpha1"
	kind:       "IngressRoute"
	metadata: {
		name:      #config.metadata.name
		namespace: #config.metadata.namespace
		labels:    #config.metadata.labels
		if #config.metadata.annotations != _|_ {
			annotations: #config.metadata.annotations
		}
	}
	spec: {
		entryPoints: [#entryPoint]

		if #config.deploymentMode == "monolith" {
			routes: [{
				match: (#_buildMatch & {_pattern: "/", _matchType: "prefix", _host: *#host | ""}).out
				kind: "Rule"
				services: [{
					name: #config.metadata.name
					port: #config.service.port
				}]
			}]
		}

		if #config.deploymentMode == "microservices" {
			routes: [
				for _svcName, _meta in #ServiceCatalog
				if _meta.kind == "http" && _svcName != "monolith"
				for _route in _meta.routes {
					match: (#_buildMatch & {_pattern: _route.pattern, _matchType: _route.matchType, _host: *#host | ""}).out
					kind:     "Rule"
					priority: _route.priority
					services: [{
						name: "\(#config.metadata.name)-\(_svcName)"
						port: #config.service.port
					}]
					if _svcName == "ui-service" {
						middlewares: [{
							name:      "\(#config.metadata.name)-ui-trailing-slash"
							namespace: #config.metadata.namespace
						}]
					}
				},
			]
		}
	}
}

#_buildMatch: {
	_pattern:   string
	_matchType: "regex" | "prefix" | "exact"
	_host:      string

	out: string
	if _matchType == "regex" {
		if _host != "" {
			out: "Host(`\(_host)`) && PathRegexp(`\(_pattern)`)"
		}
		if _host == "" {
			out: "PathRegexp(`\(_pattern)`)"
		}
	}
	if _matchType == "prefix" {
		if _host != "" {
			out: "Host(`\(_host)`) && PathPrefix(`\(_pattern)`)"
		}
		if _host == "" {
			out: "PathPrefix(`\(_pattern)`)"
		}
	}
	if _matchType == "exact" {
		if _host != "" {
			out: "Host(`\(_host)`) && Path(`\(_pattern)`)"
		}
		if _host == "" {
			out: "Path(`\(_pattern)`)"
		}
	}
}
