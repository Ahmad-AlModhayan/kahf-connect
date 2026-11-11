// Writes ../catalog/catalog.json from the manifests the SDKs define, so the
// catalog and the code can never describe a connector differently. Sign it
// afterwards with catalog_tool.dart.
// ignore_for_file: avoid_print
import 'dart:convert';
import 'dart:io';

import 'package:kahf_connect/kahf_connect.dart';

/// The phone's own calendars: built in to the Android products.
final phoneCalendar = Manifest.parse({
  'kahf_connect': '1',
  'id': 'com.kahflabs.phone-calendar',
  'name': {'en': 'Phone calendar', 'ar': 'تقويم الهاتف'},
  'description': {
    'en': 'Busy times from the calendars on this phone, including Google '
        'and Outlook accounts added to it. Nothing leaves the phone.',
    'ar': 'أوقات انشغالك من التقاويم على هذا الهاتف، ومنها حسابات Google '
        'وOutlook المضافة إليه. لا يغادر شيء الهاتف.',
  },
  'version': '1.0.0',
  'author': {'name': 'Kahf Labs', 'url': 'https://kahflabs.com'},
  'host': {'kind': 'builtin'},
  'auth': {'kind': 'none'},
  'capabilities': [
    {
      'id': 'time.busy',
      'version': 1,
      'reason': {
        'en': 'To keep planned focus off the times your calendar says you '
            'are busy.',
        'ar': 'لكي لا يُخطَّط تركيز في أوقات يقول تقويمك إنك مشغول فيها.',
      },
    }
  ],
});

void main() {
  final catalog = {
    'kahf_connect': '1',
    'issued_at': DateTime.now().toUtc().toIso8601String(),
    'key_id': 'kahf-2026-1',
    'entries': [
      for (final m in [CalendarFeedConnector.definition, phoneCalendar])
        {'reviewed': true, 'manifest': m.toJson()},
    ],
  };
  final out = File('../catalog/catalog.json')..createSync(recursive: true);
  out.writeAsStringSync(
      '${const JsonEncoder.withIndent('  ').convert(catalog)}\n');
  print('wrote ${out.path}');
}
