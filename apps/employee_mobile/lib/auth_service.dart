import 'dart:convert';
import 'dart:math';

import 'package:crypto/crypto.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_web_auth_2/flutter_web_auth_2.dart';
import 'package:http/http.dart' as http;

/// Gateway 주소. 실기기는 `adb reverse tcp:8080 tcp:8080` 후 localhost 사용.
/// 바꾸려면 `flutter run --dart-define=GATEWAY_URL=...`
const gatewayUrl = String.fromEnvironment('GATEWAY_URL', defaultValue: 'http://localhost:8080');

/// 로그인 후 앱으로 돌아오는 커스텀 스킴 (AndroidManifest의 CallbackActivity와 같아야 함)
const _callbackScheme = 'pmart-employee';

class AuthException implements Exception {
  AuthException(this.message);
  final String message;
  @override
  String toString() => message;
}

/// PMart 직원 로그인 (D20)
///
/// 1. 앱이 app_verifier를 만들고 app_challenge = BASE64URL(SHA256(app_verifier))를 로그인 시작 주소에 붙임
/// 2. 시스템 브라우저(Custom Tabs / ASWebAuthenticationSession)로 Keycloak 로그인 (임베디드 WebView 사용 안 함)
/// 3. Employee Auth Service가 pmart-employee://login-callback?ticket=... 으로 앱을 다시 엶 (토큰은 URL에 없음)
/// 4. 앱이 ticket + app_verifier를 보내 PMart Access·Refresh Token을 받음
/// 5. 토큰은 기기 보안 저장소에 보관하고, API는 Authorization: Bearer 헤더로 호출
class AuthService {
  AuthService({FlutterSecureStorage? storage}) : _storage = storage ?? const FlutterSecureStorage();

  final FlutterSecureStorage _storage;
  static const _accessKey = 'pmart_access_token';
  static const _refreshKey = 'pmart_refresh_token';

  Future<String?> get accessToken => _storage.read(key: _accessKey);

  Future<bool> get isLoggedIn async => (await _storage.read(key: _refreshKey)) != null;

  Future<void> login() async {
    final appVerifier = _randomToken();
    final appChallenge = base64Url.encode(sha256.convert(ascii.encode(appVerifier)).bytes).replaceAll('=', '');

    final startUrl = Uri.parse('$gatewayUrl/employee-auth/oauth2/start').replace(queryParameters: {
      'client': 'mobile',
      'app_challenge': appChallenge,
    });

    final String result;
    try {
      result = await FlutterWebAuth2.authenticate(url: startUrl.toString(), callbackUrlScheme: _callbackScheme);
    } catch (e) {
      throw AuthException('로그인이 취소되었습니다.');
    }

    final callback = Uri.parse(result);
    final error = callback.queryParameters['error'];
    if (error != null) throw AuthException(_loginErrorMessage(error));
    final ticket = callback.queryParameters['ticket'];
    if (ticket == null) throw AuthException('로그인 결과를 받지 못했습니다.');

    // ticket만으로는 교환할 수 없고, 로그인을 시작한 이 앱의 app_verifier가 함께 있어야 함
    await _saveTokens(await _postJson('/employee-auth/mobile/token', {'ticket': ticket, 'appVerifier': appVerifier}));
  }

  /// Refresh Token Rotation. 실패하면(만료·재사용 탐지·퇴사) 저장된 토큰을 지우고 false
  Future<bool> refresh() async {
    final refreshToken = await _storage.read(key: _refreshKey);
    if (refreshToken == null) return false;
    try {
      await _saveTokens(await _postJson('/employee-auth/mobile/refresh', {'refreshToken': refreshToken}));
      return true;
    } on AuthException {
      await _clear();
      return false;
    }
  }

  Future<void> logout() async {
    final refreshToken = await _storage.read(key: _refreshKey);
    if (refreshToken != null) {
      try {
        await _postJson('/employee-auth/mobile/logout', {'refreshToken': refreshToken});
      } catch (_) {
        // 서버 폐기에 실패해도 기기에서는 토큰을 지움
      }
    }
    await _clear();
  }

  /// PMart JWT로 API 호출. 401이면 한 번 갱신 후 재시도
  Future<ApiResult> get(String path) async {
    var response = await _authorizedGet(path);
    if (response.statusCode == 401 && await refresh()) {
      response = await _authorizedGet(path);
    }
    return ApiResult(response.statusCode, response.body);
  }

  Future<http.Response> _authorizedGet(String path) async {
    final token = await accessToken;
    return http.get(Uri.parse('$gatewayUrl$path'), headers: {
      if (token != null) 'Authorization': 'Bearer $token',
    });
  }

  Future<Map<String, dynamic>> _postJson(String path, Map<String, String> body) async {
    final response = await http.post(
      Uri.parse('$gatewayUrl$path'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode(body),
    );
    final data = response.body.isEmpty ? <String, dynamic>{} : jsonDecode(response.body) as Map<String, dynamic>;
    if (response.statusCode >= 400) {
      throw AuthException(_loginErrorMessage(data['error'] as String? ?? 'http_${response.statusCode}'));
    }
    return data;
  }

  Future<void> _saveTokens(Map<String, dynamic> tokens) async {
    await _storage.write(key: _accessKey, value: tokens['accessToken'] as String);
    await _storage.write(key: _refreshKey, value: tokens['refreshToken'] as String);
  }

  Future<void> _clear() async {
    await _storage.delete(key: _accessKey);
    await _storage.delete(key: _refreshKey);
  }

  static String _randomToken() {
    final random = Random.secure();
    return base64Url.encode(List<int>.generate(32, (_) => random.nextInt(256))).replaceAll('=', '');
  }

  static String _loginErrorMessage(String code) => switch (code) {
        'invalid_ticket' => '로그인 결과가 만료되었거나 올바르지 않습니다. 다시 로그인하세요.',
        'inactive_employee' => '재직 중이 아닌 계정입니다.',
        'unknown_employee' => '직원 정보에 등록되지 않은 계정입니다.',
        'invalid_id_token' => 'Keycloak 인증 결과를 확인하지 못했습니다.',
        _ => '로그인 실패: $code',
      };
}

class ApiResult {
  ApiResult(this.statusCode, this.body);
  final int statusCode;
  final String body;
  bool get ok => statusCode >= 200 && statusCode < 300;
}
