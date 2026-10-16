#!/usr/bin/env bash
# Поднимает Navidrome на этом компьютере (Arch) и открывает его в интернет
# через Tailscale Funnel: на телефонах ничего ставить не нужно, адрес вида
# https://<имя-пк>.<tailnet>.ts.net работает из любой сети, сертификат настоящий.
#
# Запуск:  sudo bash server/setup-navidrome.sh
set -euo pipefail

MUSIC_DIR=/srv/music   # systemd-служба Navidrome не видит /home (ProtectHome)
OWNER=${SUDO_USER:-root}

pacman -S --needed --noconfirm navidrome tailscale ffmpeg

# Папка с музыкой: пишет туда обычный пользователь, Navidrome только читает
install -d -m 0755 -o "$OWNER" -g "$OWNER" "$MUSIC_DIR"

cat > /etc/navidrome/navidrome.toml <<EOF
# https://www.navidrome.org/docs/usage/configuration-options/
DataFolder = "/var/lib/navidrome"
MusicFolder = "$MUSIC_DIR"
# Слушаем только локально: снаружи доступ идёт через Tailscale Funnel (HTTPS)
Address = "127.0.0.1"
Port = 4533
# Новые файлы подхватываются сами
Scanner.Schedule = "@every 15m"
EOF

systemctl enable --now navidrome tailscaled

echo
echo "== Вход в Tailscale: откройте ссылку, которую выведет команда ниже =="
tailscale up

# --bg: Funnel переживает перезагрузку. Если Funnel/HTTPS ещё не включены
# в аккаунте, команда выведет ссылку, где их включить, — потом запустите её снова.
tailscale funnel --bg 4533

echo
echo "Готово. Адрес сервера:"
tailscale funnel status | grep -o 'https://[^ ]*' | head -1
echo "Откройте его в браузере и создайте администратора, затем аккаунты друзьям."
echo "Музыку кладите в $MUSIC_DIR"
