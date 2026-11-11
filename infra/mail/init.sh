#!/bin/sh
# 메일 서버 초기 준비 (compose의 mail-init 서비스가 매번 실행, 이미 있으면 건너뜀)
# 1) 내부 CA와 메일 서버 TLS 인증서  2) Keycloak 발신용 noreply 계정  3) 외부 발송 차단 설정
set -eu

CERTS=/certs
CONF=/mail-config
ACCOUNTS="$CONF/postfix-accounts.cf"

apk add --no-cache openssl >/dev/null

# 1) 내부 CA + 서버 인증서 (공인 CA가 아님. Keycloak·Roundcube가 이 CA를 신뢰하도록 설정)
if [ ! -f "$CERTS/ca.crt" ]; then
  openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
    -keyout "$CERTS/ca.key" -out "$CERTS/ca.crt" -subj "/CN=PMart LAN Test CA"
  openssl req -newkey rsa:2048 -nodes -keyout "$CERTS/mail.key" -out "$CERTS/mail.csr" -subj "/CN=mail.${MAIL_DOMAIN}"
  printf 'subjectAltName=DNS:mailserver,DNS:mail.%s\nextendedKeyUsage=serverAuth\n' "$MAIL_DOMAIN" > "$CERTS/san.ext"
  openssl x509 -req -in "$CERTS/mail.csr" -CA "$CERTS/ca.crt" -CAkey "$CERTS/ca.key" -CAcreateserial \
    -days 825 -out "$CERTS/mail.crt" -extfile "$CERTS/san.ext"
  rm -f "$CERTS/mail.csr" "$CERTS/san.ext"
  echo "내부 CA와 메일 서버 인증서를 만들었습니다 (SAN: mailserver, mail.$MAIL_DOMAIN)"
fi
chmod 644 "$CERTS/ca.crt" "$CERTS/mail.crt" "$CERTS/mail.key"

# 2) Keycloak이 비밀번호 설정 메일을 보낼 noreply 계정 (SMTP 비밀번호 인증용)
touch "$ACCOUNTS"
NOREPLY="noreply@$MAIL_DOMAIN"
if ! grep -q "^$NOREPLY|" "$ACCOUNTS"; then
  HASH=$(openssl passwd -6 "$MAIL_NOREPLY_PASSWORD")
  echo "$NOREPLY|{SHA512-CRYPT}$HASH" >> "$ACCOUNTS"
  echo "메일 계정을 만들었습니다: $NOREPLY"
fi
# Employee Service 컨테이너(비 root)가 직원 메일함을 추가할 수 있게 쓰기 허용
chmod 666 "$ACCOUNTS"

# 3) 외부 인터넷으로 메일이 나가지 않도록 차단 (pmart.test 내부 배달만 허용)
cp /init/postfix-main.cf "$CONF/postfix-main.cf"
echo "메일 초기 준비 완료"
