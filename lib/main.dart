import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setSystemUIOverlayStyle(const SystemUiOverlayStyle(
    statusBarColor: Colors.transparent,
    statusBarIconBrightness: Brightness.light,
    systemNavigationBarColor: _ink,
  ));
  runApp(const VmApp());
}

const _ink = Color(0xFF03060F);
const _panel = Color(0xFF0A1226);
const _blue = Color(0xFF1E6BFF);
const _cyan = Color(0xFF00C8FF);
const _silver = Color(0xFFC9D3E6);
const _muted = Color(0xFF7F8CA8);

class VmApp extends StatelessWidget {
  const VmApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'VMloader-spaceman',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: _ink,
        colorScheme: const ColorScheme.dark(primary: _blue, secondary: _cyan),
        useMaterial3: true,
      ),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  static const _ch = MethodChannel('vmloader/native');

  bool _overlay = false;
  bool _access = false;
  bool _running = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    try {
      final m = await _ch.invokeMapMethod<String, dynamic>('status');
      if (!mounted || m == null) return;
      setState(() {
        _overlay = m['overlay'] == true;
        _access = m['access'] == true;
        _running = m['running'] == true;
      });
    } catch (_) {}
  }

  Future<void> _call(String method) async {
    try {
      await _ch.invokeMethod(method);
    } catch (_) {}
    await Future.delayed(const Duration(milliseconds: 300));
    _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final ready = _overlay && _access;
    return Scaffold(
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(22, 28, 22, 28),
          children: [
            Center(
              child: Container(
                width: 168,
                height: 168,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  boxShadow: [
                    BoxShadow(
                      color: _blue.withOpacity(0.55),
                      blurRadius: 44,
                      spreadRadius: 2,
                    ),
                  ],
                ),
                child: ClipOval(
                  child: Image.asset('assets/logo.png', fit: BoxFit.cover),
                ),
              ),
            ),
            const SizedBox(height: 22),
            const Text(
              'VMloader-spaceman',
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 27,
                fontWeight: FontWeight.w800,
                color: Colors.white,
                letterSpacing: 0.4,
              ),
            ),
            const SizedBox(height: 4),
            const Text(
              'Create by SpacemanBuilder',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 14, color: _cyan),
            ),
            const SizedBox(height: 30),
            _StepCard(
              done: _overlay,
              title: 'Tampil di atas aplikasi lain',
              subtitle: 'Agar jendela mengambang bisa muncul di atas Vita Mahjong.',
              action: _overlay ? 'Aktif' : 'Izinkan',
              onTap: _overlay ? null : () => _call('requestOverlay'),
            ),
            const SizedBox(height: 12),
            _StepCard(
              done: _access,
              title: 'Layanan aksesibilitas',
              subtitle:
                  'Cari VMloader-spaceman di daftar lalu aktifkan. Dipakai untuk mengetuk layar dan membaca papan game.',
              action: _access ? 'Aktif' : 'Buka pengaturan',
              onTap: _access ? null : () => _call('openAccessibility'),
            ),
            const SizedBox(height: 26),
            SizedBox(
              height: 54,
              child: FilledButton(
                style: FilledButton.styleFrom(
                  backgroundColor: _running ? const Color(0xFF2A3350) : _blue,
                  disabledBackgroundColor: const Color(0xFF111A33),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                  ),
                ),
                onPressed: !ready
                    ? null
                    : () => _call(_running ? 'stopOverlay' : 'startOverlay'),
                child: Text(
                  _running
                      ? 'Tutup jendela mengambang'
                      : 'Tampilkan jendela mengambang',
                  style: const TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w700,
                    color: Colors.white,
                  ),
                ),
              ),
            ),
            const SizedBox(height: 22),
            const Text(
              'Cara pakai',
              style: TextStyle(
                fontSize: 15,
                fontWeight: FontWeight.w700,
                color: _silver,
              ),
            ),
            const SizedBox(height: 8),
            const Text(
              'Buka Vita Mahjong, ketuk logo mengambang, isi target level '
              '(0 = tanpa batas), lalu tekan Auto Play. '
              'Ketuk logo lagi kapan saja untuk berhenti.',
              style: TextStyle(fontSize: 14, height: 1.5, color: _muted),
            ),
            const SizedBox(height: 10),
            const Text(
              'Butuh Android 11 atau lebih baru.',
              style: TextStyle(fontSize: 13, color: _muted),
            ),
          ],
        ),
      ),
    );
  }
}

class _StepCard extends StatelessWidget {
  const _StepCard({
    required this.done,
    required this.title,
    required this.subtitle,
    required this.action,
    required this.onTap,
  });

  final bool done;
  final String title;
  final String subtitle;
  final String action;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: _panel,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: done ? _cyan.withOpacity(0.6) : _blue.withOpacity(0.35),
          width: 1.2,
        ),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(
            done ? Icons.check_circle : Icons.radio_button_unchecked,
            color: done ? _cyan : _muted,
            size: 24,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: const TextStyle(
                    fontSize: 15.5,
                    fontWeight: FontWeight.w700,
                    color: Colors.white,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  subtitle,
                  style: const TextStyle(
                    fontSize: 13,
                    height: 1.45,
                    color: _muted,
                  ),
                ),
                const SizedBox(height: 10),
                SizedBox(
                  height: 36,
                  child: OutlinedButton(
                    style: OutlinedButton.styleFrom(
                      foregroundColor: done ? _muted : _cyan,
                      side: BorderSide(color: done ? _muted : _cyan),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(10),
                      ),
                    ),
                    onPressed: onTap,
                    child: Text(action),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
