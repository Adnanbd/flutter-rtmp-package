extension L<T> on List<T>? {
  T? firstWhereOrNull(bool Function(T element) test) {
    if (this == null) return null;
    try {
      return this!.firstWhere(test);
    } catch (e) {
      return null;
    }
  }

  void swap(int first, int second) {
    final temp = this![first];
    this![first] = this![second];
    this![second] = temp;
  }
}
