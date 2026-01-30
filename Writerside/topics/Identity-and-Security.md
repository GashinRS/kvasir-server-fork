# Identity & Security

Kvasir implements [authentication](Authentication.md) by processing JWT tokens in the `Authorization` header of incoming
requests, both `Bearer` and `DPoP` tokens are supported. The tokens are validated according to the OpenID Connect and
OAuth 2 standards.

When a WebID claim is present in the token, Kvasir performs additional verification to ensure the token was issued by an
OIDC server that is trusted by the WebID owner (per [Solid OIDC specification](https://solidproject.org/TR/oidc)).
_WebIDs can be disabled by setting `auth.enable-solid-web-id` to false in the Pod configuration._

On top of this authentication layer, Kvasir integrates multiple [authorization mechanisms](Access-Control.md) via a
modular, pluggable
architecture, based around [OpenFGA](https://openfga.dev):

- Using **OpenFGA** relationship-based access control (ReBAC), fine-grained access control policies can be expressed and
  enforced for Kvasir HTTP resources. These policies are local to the Kvasir server instance.
- OpenFGA relationships can also be used to express that policy decisions for specific resources (or resource
  hierarchies) should be delegated to external systems. Kvasir currently supports the following integrations:
    - Delegate to **[A4DS](https://spec.knows.idlab.ugent.be/A4DS/L1/latest/)/UMA 2.0** compliant Authorization Servers.
    - Delegate to any external system that implements a specific **HTTP endpoint** for policy decision requests.

## Keycloak

By default, Kvasir installations come with a Keycloak server that is used as a OIDC-compliant Identity Provider for
authentication.
OpenFGA is used to manage authorization. Some of the main benefits for choosing Keycloak are:

- Uses battle-tested
  standards ([OpenID Connect 1.0](https://openid.net/specs/openid-connect-core-1_0.html), [OAuth 2.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-11))
- Also acts as an Identity Broker, allowing integration with other OpenId Providers for authentication.
- Widespread use & large support community
- Client libraries available in multiple languages (not all
  official): [javascript](https://www.keycloak.org/securing-apps/javascript-adapter), [java](https://github.com/keycloak/keycloak-client), [python](https://pypi.org/project/python-keycloak/)

## OpenFGA

OpenFGA is an open-source implementation of the Google Zanzibar paper, a globally distributed
authorization system that manages permissions at scale (powering authorization policies for Google Services such as
YouTube, Drive, Calendar, Cloud and Maps.).

More information on OpenFga can be found on their website: [https://openfga.dev](https://openfga.dev).