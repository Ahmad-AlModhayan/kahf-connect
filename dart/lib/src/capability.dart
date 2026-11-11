/// The data each capability carries (SPEC §4), and the checks every answer
/// passes before a product sees it. A connector's answer is outside data:
/// it is parsed strictly, and one bad record fails the call rather than
/// slipping through.
library;

/// A time the person is not free (`time.busy@1`).
class BusyWindow {
  BusyWindow(this.start, this.end, {this.label, this.allDay = false});

  final DateTime start;
  final DateTime end;

  /// Personal; dropped unless the grant keeps labels (SPEC §4).
  final String? label;
  final bool allDay;

  bool overlaps(DateTime from, DateTime to) =>
      start.isBefore(to) && end.isAfter(from);

  BusyWindow withoutLabel() => BusyWindow(start, end, allDay: allDay);

  Map<String, Object?> toJson() => {
        'start': start.toUtc().toIso8601String(),
        'end': end.toUtc().toIso8601String(),
        if (label != null) 'label': label,
        if (allDay) 'all_day': true,
      };

  static BusyWindow fromJson(Map<String, Object?> j) => BusyWindow(
        DateTime.parse(j['start'] as String),
        DateTime.parse(j['end'] as String),
        label: j['label'] as String?,
        allDay: j['all_day'] == true,
      );

  @override
  bool operator ==(Object other) =>
      other is BusyWindow &&
      other.start == start &&
      other.end == end &&
      other.label == label &&
      other.allDay == allDay;
  @override
  int get hashCode => Object.hash(start, end, label, allDay);
  @override
  String toString() =>
      'BusyWindow($start–$end${label == null ? '' : ' $label'})';
}

/// Something an agent may read (`context.read@1`).
class ContextItem {
  ContextItem(
      {required this.id,
      required this.title,
      required this.text,
      this.url,
      this.updatedAt});

  final String id;
  final String title;
  final String text;
  final Uri? url;
  final DateTime? updatedAt;

  Map<String, Object?> toJson() => {
        'id': id,
        'title': title,
        'text': text,
        if (url != null) 'url': '$url',
        if (updatedAt != null)
          'updated_at': updatedAt!.toUtc().toIso8601String(),
      };
}

/// An answer that breaks its capability's contract. [rule] is stable (the
/// conformance fixtures name it).
class CapabilityError implements Exception {
  CapabilityError(this.rule, [this.detail = '']);
  final String rule;
  final String detail;
  @override
  String toString() => 'CapabilityError($rule: $detail)';
}

final _offset = RegExp(r'(Z|[+-]\d{2}:?\d{2})$');

DateTime _time(Object? raw, String field) {
  if (raw is! String || !_offset.hasMatch(raw)) {
    throw CapabilityError('time-offset', '$field: $raw');
  }
  final t = DateTime.tryParse(raw);
  if (t == null) throw CapabilityError('time', '$field: $raw');
  return t;
}

/// `time.busy@1` output, checked against [from, to).
List<BusyWindow> parseBusy(Object? output, DateTime from, DateTime to) {
  if (output is! Map || output['windows'] is! List) {
    throw CapabilityError('shape', 'no windows');
  }
  final list = output['windows'] as List;
  if (list.length > 2000) throw CapabilityError('too-many', '${list.length}');
  final out = <BusyWindow>[];
  for (final (i, w) in list.indexed) {
    if (w is! Map) throw CapabilityError('shape', 'windows[$i]');
    final start = _time(w['start'], 'windows[$i].start');
    final end = _time(w['end'], 'windows[$i].end');
    if (!end.isAfter(start)) throw CapabilityError('order', 'windows[$i]');
    final label = w['label'];
    if (label != null && (label is! String || label.length > 200)) {
      throw CapabilityError('label', 'windows[$i]');
    }
    final window = BusyWindow(start, end,
        label: label as String?, allDay: w['all_day'] == true);
    if (!window.overlaps(from, to)) {
      throw CapabilityError('out-of-range', 'windows[$i]');
    }
    out.add(window);
  }
  return out;
}

/// `context.read@1` output.
List<ContextItem> parseContext(Object? output, {int limit = 50}) {
  if (output is! Map || output['items'] is! List) {
    throw CapabilityError('shape', 'no items');
  }
  final list = output['items'] as List;
  if (list.length > 50 || list.length > limit) {
    throw CapabilityError('too-many', '${list.length}');
  }
  final seen = <String>{};
  final out = <ContextItem>[];
  for (final (i, it) in list.indexed) {
    if (it is! Map) throw CapabilityError('shape', 'items[$i]');
    final id = it['id'], title = it['title'], text = it['text'];
    if (id is! String || id.isEmpty || id.length > 200) {
      throw CapabilityError('id', 'items[$i]');
    }
    if (!seen.add(id)) throw CapabilityError('id-duplicate', id);
    if (title is! String || title.length > 300) {
      throw CapabilityError('title', 'items[$i]');
    }
    if (text is! String || text.length > 20000) {
      throw CapabilityError('text', 'items[$i]');
    }
    final url = it['url'];
    Uri? u;
    if (url != null) {
      u = url is String ? Uri.tryParse(url) : null;
      if (u == null || !u.hasScheme) throw CapabilityError('url', 'items[$i]');
    }
    final updated = it['updated_at'];
    out.add(ContextItem(
      id: id,
      title: title,
      text: text,
      url: u,
      updatedAt:
          updated == null ? null : _time(updated, 'items[$i].updated_at'),
    ));
  }
  return out;
}
