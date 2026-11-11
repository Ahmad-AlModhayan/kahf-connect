/// The catalog of connectors Kahf Labs has reviewed (SPEC §8): signed with
/// Ed25519 over the exact bytes of `catalog.json`, so a catalog fetched over
/// any network, from any mirror, is only believed when the key says so.
library;

import 'dart:convert';

import 'package:cryptography/cryptography.dart';

import 'manifest.dart';

/// The keys catalogs are signed with: `key_id` → base64 Ed25519 public key.
/// A new key is added here before it signs anything; a retired one stays
/// until every catalog it signed has been replaced.
abstract final class KahfKeys {
  static const Map<String, String> catalog = {
    'kahf-2026-1': 'yYJg7VinUy6h9gipuzXpxuKUROJ5+xrcQxkVLrjVF5s=',
  };
}

class CatalogEntry {
  CatalogEntry(this.manifest);
  final Manifest manifest;

  /// Every catalog entry was reviewed; one added by address was not.
  bool get reviewed => true;
}

class Catalog {
  Catalog._(this.issuedAt, this.keyId, this.entries);

  final DateTime issuedAt;
  final String keyId;
  final List<CatalogEntry> entries;

  CatalogEntry? entry(String id) {
    for (final e in entries) {
      if (e.manifest.id == id) return e;
    }
    return null;
  }

  /// Verifies [signature] (base64) over [bytes] with the key the catalog
  /// names, then parses it. Throws [CatalogError] when either fails; an
  /// entry with a bad manifest fails the whole catalog, so a signed catalog
  /// is all good or not used.
  static Future<Catalog> verify(List<int> bytes, String signature,
      {Map<String, String> keys = KahfKeys.catalog}) async {
    final Object? json;
    try {
      json = jsonDecode(utf8.decode(bytes));
    } on FormatException {
      throw CatalogError('json');
    }
    if (json is! Map) throw CatalogError('shape');
    if (json['kahf_connect'] != '1') throw CatalogError('version-of-spec');
    final keyId = json['key_id'];
    final key = keyId is String ? keys[keyId] : null;
    if (key == null) throw CatalogError('key-unknown', '$keyId');

    final List<int> sig;
    try {
      sig = base64.decode(signature.trim());
    } on FormatException {
      throw CatalogError('signature');
    }
    final ok = await Ed25519().verify(bytes,
        signature: Signature(sig,
            publicKey: SimplePublicKey(base64.decode(key),
                type: KeyPairType.ed25519)));
    if (!ok) throw CatalogError('signature');

    final issued = json['issued_at'];
    final at = issued is String ? DateTime.tryParse(issued) : null;
    if (at == null) throw CatalogError('issued-at');
    final raw = json['entries'];
    if (raw is! List) throw CatalogError('shape');
    final entries = <CatalogEntry>[];
    for (final e in raw) {
      if (e is! Map || e['reviewed'] != true || e['manifest'] is! Map) {
        throw CatalogError('entry');
      }
      try {
        entries.add(CatalogEntry(
            Manifest.parse((e['manifest'] as Map).cast<String, Object?>())));
      } on ManifestError catch (m) {
        throw CatalogError('entry-manifest', '${m.rule}: ${m.detail}');
      }
    }
    final ids = <String>{};
    for (final e in entries) {
      if (!ids.add(e.manifest.id)) {
        throw CatalogError('entry-duplicate', e.manifest.id);
      }
    }
    return Catalog._(at, keyId as String, List.unmodifiable(entries));
  }
}

class CatalogError implements Exception {
  CatalogError(this.rule, [this.detail = '']);
  final String rule;
  final String detail;
  @override
  String toString() => 'CatalogError($rule: $detail)';
}
