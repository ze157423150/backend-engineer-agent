#!/usr/bin/env bash
set -euo pipefail

# Official Docker APT repository installation; requires administrator access.
# https://docs.docker.com/engine/install/ubuntu/
docker_eval_user="${1:-${SUDO_USER:-}}"
if [[ "${EUID}" -ne 0 ]]; then
    printf '%s\n' 'Administrator access is required.' 'Run in your server terminal: sudo bash scripts/install-docker-ubuntu.sh'
    exit 2
fi
if [[ -z "${docker_eval_user}" || "${docker_eval_user}" == root ]]; then
    printf '%s\n' 'Specify the non-root evaluation user: bash scripts/install-docker-ubuntu.sh <username>'
    exit 2
fi
id "${docker_eval_user}" >/dev/null
# OS metadata is provided by the installed operating system.
source /etc/os-release
if [[ "${ID}" != ubuntu ]]; then
    printf '%s\n' 'This installer supports Ubuntu only.'
    exit 2
fi

apt-get update
apt-get install -y ca-certificates curl
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
docker_eval_arch="$(dpkg --print-architecture)"
cat > /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: ${UBUNTU_CODENAME:-${VERSION_CODENAME}}
Components: stable
Architectures: ${docker_eval_arch}
Signed-By: /etc/apt/keyrings/docker.asc
EOF
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker
usermod -aG docker "${docker_eval_user}"
docker info --format 'Docker server version: {{.ServerVersion}}'
printf '%s\n' "Docker installed. Reconnect SSH as ${docker_eval_user} to activate docker group membership."
