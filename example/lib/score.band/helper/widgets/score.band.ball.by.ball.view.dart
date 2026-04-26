
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_rtmp_broadcaster_example/extensions/extensions.dart';

class ScorebandBallByBallView extends ConsumerWidget {
  final bool mediumMode;
  final double fontSize;
  const ScorebandBallByBallView({super.key, required this.mediumMode, required this.fontSize});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final textStyleForBand = TextStyle(
      fontFamily: 'RobotoCondensed',
      color: Colors.white,
    );
    String inningsText;
    if (1 == 1) {
      inningsText = '1ST INNNIGS';
    } else {
      inningsText = '2ND INNNIGS';
    }
    return LayoutBuilder(builder: (context, cons) {
      final availableWidth = cons.maxWidth; // exact available width
      return SizedBox(
        height: context.mobileWidth ? 18 : 35,
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            SizedBox(
              width: availableWidth * 0.32,
              child: FittedBox(
                fit: BoxFit.scaleDown,
                alignment: Alignment.center,
                child: Text(
                  inningsText,
                  style: textStyleForBand.copyWith(
                    fontSize: fontSize - 3,
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ),
            ),
            SizedBox(width: context.mobileWidth ? 3 : 6),
            Expanded(
              child: SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: Row(
                  children: List<Widget>.generate(
                    6,
                    (index) {
                      return SizedBox(
                        width: 25,
                        height: 25,
                        // margin: const EdgeInsets.symmetric(horizontal: 3),
                        // decoration: BoxDecoration(color: Colors.white, shape: BoxShape.circle),
                        // child: Center(
                        //   child: Text(
                        //     '${index + 1}',
                        //     style: textStyleForBand.copyWith(
                        //       fontSize: fontSize,
                        //       color: Colors.black,
                        //       fontWeight: FontWeight.bold,
                        //     ),
                        //   ),
                        // ),
                      );
                    },
                  ),
                ),
              ),
            ),
          ],
        ),
      );
    });
  }
}
