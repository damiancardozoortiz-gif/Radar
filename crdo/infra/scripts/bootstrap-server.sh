#!/usr/bin/env bash
# C.R.D.O. — Endurecimiento inicial del VPS (BORRADOR, no ejecutado).
# Ubuntu Server 24.04 LTS. Ejecutar UNA vez como root, tras crear el servidor.
# Uso: TS_AUTHKEY=tskey-... ADMIN_PUBKEY="ssh-ed25519 AAAA..." bash bootstrap-server.sh
set -euo pipefail

: "${TS_AUTHKEY:?Falta TS_AUTHKEY (Tailscale, un solo uso)}"
: "${ADMIN_PUBKEY:?Falta ADMIN_PUBKEY (tu llave SSH pública)}"
ADMIN_USER="${ADMIN_USER:-crdo}"

log() { printf '\n==> %s\n' "$*"; }

[[ $EUID -eq 0 ]] || { echo "Ejecutar como root" >&2; exit 1; }
grep -q 'VERSION_ID="24.04"' /etc/os-release || { echo "Se esperaba Ubuntu 24.04" >&2; exit 1; }

log "1. Actualizar sistema y parches automáticos de seguridad"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get upgrade -y
apt-get install -y unattended-upgrades ufw fail2ban restic logrotate curl ca-certificates gnupg jq
cat >/etc/apt/apt.conf.d/52crdo-unattended <<'EOF'
Unattended-Upgrade::Automatic-Reboot "true";
Unattended-Upgrade::Automatic-Reboot-Time "04:00";
EOF
systemctl enable --now unattended-upgrades

log "2. Zona horaria y swap"
timedatectl set-timezone America/Argentina/Buenos_Aires
if ! swapon --show | grep -q /swapfile; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
  echo '/swapfile none swap sw 0 0' >>/etc/fstab
fi

log "3. Usuario administrador con llave SSH"
id "$ADMIN_USER" &>/dev/null || adduser --disabled-password --gecos "" "$ADMIN_USER"
usermod -aG sudo "$ADMIN_USER"
install -d -m 700 -o "$ADMIN_USER" -g "$ADMIN_USER" "/home/$ADMIN_USER/.ssh"
echo "$ADMIN_PUBKEY" >"/home/$ADMIN_USER/.ssh/authorized_keys"
chown "$ADMIN_USER:$ADMIN_USER" "/home/$ADMIN_USER/.ssh/authorized_keys"
chmod 600 "/home/$ADMIN_USER/.ssh/authorized_keys"

cat >/etc/ssh/sshd_config.d/10-crdo.conf <<'EOF'
PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
MaxAuthTries 3
EOF
sshd -t && systemctl reload ssh

log "4. Tailscale (acceso administrativo privado)"
curl -fsSL https://tailscale.com/install.sh | sh
tailscale up --authkey="$TS_AUTHKEY" --hostname=crdo-prod --ssh=false

log "5. Firewall: nada entrante salvo SSH por Tailscale"
ufw default deny incoming
ufw default allow outgoing
ufw allow in on tailscale0 to any port 22 proto tcp
ufw --force enable

log "6. Docker Engine + Compose (repositorio oficial)"
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu noble stable" \
  >/etc/apt/sources.list.d/docker.list
apt-get update -y
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
cat >/etc/docker/daemon.json <<'EOF'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "5" },
  "live-restore": true,
  "no-new-privileges": true
}
EOF
systemctl restart docker
docker network inspect crdo_app &>/dev/null || docker network create --internal crdo_app

log "7. Estructura /opt/crdo"
install -d -m 755 /opt/crdo /opt/crdo/app /opt/crdo/releases /opt/crdo/logs
install -d -m 700 /opt/crdo/secrets /opt/crdo/backups
install -d -m 700 /opt/crdo/data /opt/crdo/data/prod /opt/crdo/data/staging

log "Listo. Pasos manuales pendientes:"
cat <<EOF
  - Verificar acceso: ssh $ADMIN_USER@crdo-prod (por Tailscale) ANTES de cerrar esta sesión.
  - Quitar la regla SSH temporal del firewall de Hetzner.
  - Continuar con §E.3 del diseño (despliegue).
EOF
