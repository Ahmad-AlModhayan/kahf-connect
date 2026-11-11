import 'dart:io';

import 'package:kahf_connect/kahf_connect.dart';
import 'package:test/test.dart';

/// The catalog in this repository verifies with the key the SDK carries.
void main() {
  test('catalog/catalog.json is signed by kahf-2026-1', () async {
    final c = await Catalog.verify(
        File('../catalog/catalog.json').readAsBytesSync(),
        File('../catalog/catalog.json.sig').readAsStringSync());
    expect(c.keyId, 'kahf-2026-1');
    expect(c.entry(CalendarFeedConnector.id), isNotNull);
  });
}
