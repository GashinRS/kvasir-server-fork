# Justfile for kvasir-server-releasaurus

# Dry-run the Releasaurus release-pr locally.
# Uses --forge local so no remote forge is contacted and no PR is created.
# Releasaurus requires the URL path to match the local filesystem path, so we
# construct the repo URL from justfile_directory() rather than hardcoding it.
release-dry-run:
    releasaurus release-pr \
        --forge local \
        --repo "https://localhost{{justfile_directory()}}" \
        --base-branch main \
        --dry-run
