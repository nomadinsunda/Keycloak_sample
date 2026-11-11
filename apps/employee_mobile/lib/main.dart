import 'dart:convert';

import 'package:flutter/material.dart';

import 'auth_service.dart';

void main() => runApp(const EmployeeApp());

class EmployeeApp extends StatelessWidget {
  const EmployeeApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'PMart 직원',
      theme: ThemeData(colorSchemeSeed: const Color(0xFF0B6BCB), useMaterial3: true),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  final _auth = AuthService();
  bool _loading = true;
  bool _loggedIn = false;
  Map<String, dynamic>? _me;
  String? _message;
  String? _apiResult;

  @override
  void initState() {
    super.initState();
    _restore();
  }

  Future<void> _restore() async {
    if (await _auth.isLoggedIn) {
      await _loadMe();
    }
    setState(() => _loading = false);
  }

  Future<void> _loadMe() async {
    final result = await _auth.get('/employee-auth/me');
    setState(() {
      _loggedIn = result.ok;
      _me = result.ok ? jsonDecode(result.body) as Map<String, dynamic> : null;
      if (!result.ok) _message = '로그인이 만료되었습니다. 다시 로그인하세요.';
    });
  }

  Future<void> _login() async {
    setState(() {
      _loading = true;
      _message = null;
    });
    try {
      await _auth.login();
      await _loadMe();
    } on AuthException catch (e) {
      setState(() => _message = e.message);
    } catch (e) {
      setState(() => _message = 'Gateway에 연결할 수 없습니다 ($gatewayUrl). adb reverse 설정을 확인하세요.\n$e');
    } finally {
      setState(() => _loading = false);
    }
  }

  Future<void> _logout() async {
    await _auth.logout();
    setState(() {
      _loggedIn = false;
      _me = null;
      _apiResult = null;
    });
  }

  Future<void> _callApi(String path) async {
    final result = await _auth.get(path);
    setState(() => _apiResult = '$path\nHTTP ${result.statusCode}\n${result.body}');
    if (result.statusCode == 401) await _loadMe();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('PMart 직원'),
        actions: [
          if (_loggedIn) IconButton(onPressed: _logout, icon: const Icon(Icons.logout), tooltip: '로그아웃'),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : Padding(
              padding: const EdgeInsets.all(16),
              child: _loggedIn ? _buildDashboard(context) : _buildLogin(context),
            ),
    );
  }

  Widget _buildLogin(BuildContext context) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text('직원 계정(Keycloak)으로 로그인합니다.', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          const Text('시스템 브라우저에서 로그인한 뒤 앱으로 돌아옵니다.'),
          const SizedBox(height: 24),
          FilledButton.icon(onPressed: _login, icon: const Icon(Icons.login), label: const Text('로그인')),
          if (_message != null) ...[
            const SizedBox(height: 16),
            Text(_message!, style: TextStyle(color: Theme.of(context).colorScheme.error), textAlign: TextAlign.center),
          ],
          const SizedBox(height: 24),
          Text('Gateway: $gatewayUrl', style: Theme.of(context).textTheme.bodySmall),
        ],
      ),
    );
  }

  Widget _buildDashboard(BuildContext context) {
    final roles = (_me?['roles'] as List?)?.cast<String>() ?? const [];
    return ListView(
      children: [
        Card(
          child: ListTile(
            leading: const Icon(Icons.badge),
            title: Text('${_me?['lastName'] ?? ''}${_me?['firstName'] ?? ''} (${_me?['iamId'] ?? ''})'),
            subtitle: Text('사번: ${_me?['employeeNo'] ?? ''}\n역할: ${roles.join(', ')}\n토큰 만료: ${_me?['expiresAt'] ?? ''}'),
            isThreeLine: true,
          ),
        ),
        const SizedBox(height: 8),
        const Text('PMart JWT는 기기 보안 저장소에 보관되고, API 호출 시 Authorization: Bearer 헤더로 전송됩니다.'),
        const SizedBox(height: 16),
        Wrap(spacing: 8, runSpacing: 8, children: [
          OutlinedButton(onPressed: () => _callApi('/sample-api/hello'), child: const Text('모든 직원 API')),
          OutlinedButton(onPressed: () => _callApi('/sample-api/hr-only'), child: const Text('HR_ADMIN 전용 API')),
          OutlinedButton(
            onPressed: () async {
              final ok = await _auth.refresh();
              setState(() => _apiResult = ok ? '토큰 갱신 성공 (Refresh Token Rotation)' : '토큰 갱신 실패');
              if (!ok) await _loadMe();
            },
            child: const Text('토큰 갱신'),
          ),
        ]),
        if (_apiResult != null) ...[
          const SizedBox(height: 16),
          Card(child: Padding(padding: const EdgeInsets.all(12), child: SelectableText(_apiResult!))),
        ],
      ],
    );
  }
}
