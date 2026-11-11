<?php
// Roundcube ↔ Keycloak (D22)
// 직원은 IAM 계정(Keycloak)으로 웹메일에 로그인하고, Roundcube는 받은 Access Token으로
// IMAP·SMTP에 OAuth(XOAUTH2) 로그인합니다. 메일 서버(Dovecot)는 그 토큰을 Keycloak userinfo로 검증합니다.

$realm_public   = getenv('PUBLIC_BASE_URL') . '/iam/realms/pmart-employees';   // 브라우저가 이동하는 주소 (Gateway 경유)
$realm_internal = 'http://keycloak:8080/iam/realms/pmart-employees';         // 서버 간 호출 (Gateway 경유 안 함)

$config['oauth_provider'] = 'generic';
$config['oauth_provider_name'] = 'PMart IAM 계정';
$config['oauth_client_id'] = 'pmart-webmail';
$config['oauth_client_secret'] = getenv('WEBMAIL_CLIENT_SECRET');
$config['oauth_issuer'] = $realm_public;
$config['oauth_auth_uri'] = $realm_public . '/protocol/openid-connect/auth';
$config['oauth_token_uri'] = $realm_internal . '/protocol/openid-connect/token';
$config['oauth_identity_uri'] = $realm_internal . '/protocol/openid-connect/userinfo';
$config['oauth_jwks_uri'] = $realm_internal . '/protocol/openid-connect/certs';
$config['oauth_logout_uri'] = $realm_public . '/protocol/openid-connect/logout';
$config['oauth_pkce'] = 'S256';
$config['oauth_scope'] = 'openid email profile';
// IAM 계정 ID(= Keycloak 이메일)를 메일 계정으로 사용
$config['oauth_identity_fields'] = ['email'];
// 로그인 화면 없이 바로 Keycloak 로그인으로 이동
$config['oauth_login_redirect'] = true;

// 메일 서버: STARTTLS, 내부 CA로 인증서 검증 (검증을 끄지 않음)
$tls = ['ssl' => ['cafile' => '/certs/ca.crt', 'verify_peer' => true, 'verify_peer_name' => true, 'peer_name' => 'mailserver']];
$config['imap_host'] = 'tls://mailserver:143';
$config['smtp_host'] = 'tls://mailserver:587';
$config['imap_conn_options'] = $tls;
$config['smtp_conn_options'] = $tls;
$config['mail_domain'] = getenv('IAM_DOMAIN');
