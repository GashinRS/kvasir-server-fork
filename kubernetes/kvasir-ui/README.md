# Installation

Change the hostname of `test-values.yaml` to your desired hostname (see Kvasir installation readme at `helm/kvasir/README.md`) for instruction to set up the hostname).

```bash
helm install kvasir-ui ./kvasir-ui -n kvasir --create-namespace -f kvasir-ui/test-values.yaml
```
