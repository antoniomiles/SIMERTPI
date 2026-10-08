import 'package:flutter/material.dart';

import 'app/bootstrap/bootstrap.dart';
import 'core/push/firebase_push_runtime.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(Bootstrap(pushRuntime: FirebasePushRuntime.fromDefines()));
}
