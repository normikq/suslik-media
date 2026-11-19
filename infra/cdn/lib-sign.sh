# Подпись CDN (docs/HLS.md §6): sig = base64url_без_паддинга(md5_raw("<exp><hls_id> <secret>")).
# Использование: . lib-sign.sh; hls_sign <exp> <hls_id> <secret>
hls_sign() {
  printf '%s%s %s' "$1" "$2" "$3" | openssl md5 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '='
}
