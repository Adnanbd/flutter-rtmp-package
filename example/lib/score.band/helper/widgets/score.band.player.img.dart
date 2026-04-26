import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

class ScorebandPlayerImage extends ConsumerWidget {
  final String? imgUrl;
  final double size;
  const ScorebandPlayerImage({super.key, this.imgUrl, this.size = 10});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(6),
      child: Image.network(
        imgUrl ?? '',
        width: size,
        height: size,
        fit: BoxFit.cover,
        loadingBuilder: (context, child, loadingProgress) {
          if (loadingProgress == null) return child;
          return SizedBox(
            width: size,
            height: size,
            child: Center(
              child: CircularProgressIndicator(
                strokeWidth: 1,
                value: loadingProgress.expectedTotalBytes != null
                    ? loadingProgress.cumulativeBytesLoaded / loadingProgress.expectedTotalBytes!
                    : null,
              ),
            ),
          );
        },
        errorBuilder: (context, error, stackTrace) {
          return Container(
            width: size,
            height: size,
            color: Colors.grey.shade300,
            child: const Icon(Icons.person, color: Colors.grey),
          );
        },
      ),
    );
  }
}