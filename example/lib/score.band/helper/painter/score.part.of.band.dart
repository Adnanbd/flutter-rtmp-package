import 'package:flutter/material.dart';

//Add this CustomPaint widget to the Widget Tree
// CustomPaint(
//     size: Size(183, 32),
//     painter: RPSCustomPainter(),
// )

//Copy this CustomPainter code to the Bottom of the File
class RPSCustomPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final double w = size.width;
    final double h = size.height;
    final double sx = w / 183; // x scale factor
    final double sy = h / 32; // y scale factor

    Path path_0 = Path();
    path_0.moveTo(18.2624 * sx, 14.8957 * sy);
    path_0.cubicTo(14.8679 * sx, 20 * sy, 10.1667 * sx, 30.6667 * sy, 0.166687 * sx, h);
    path_0.lineTo(40.1667 * sx, h);
    path_0.lineTo(40.1667 * sx, 0);
    path_0.cubicTo(28.2627 * sx, 0, 23.2917 * sx, 7.33333 * sy, 18.2624 * sx, 14.8957 * sy);
    path_0.close();

    Paint paint0Fill = Paint()..style = PaintingStyle.fill;
    paint0Fill.color = Colors.white.withOpacity(1.0);
    canvas.drawPath(path_0, paint0Fill);

    // Middle gap
    Paint middleFill = Paint()..style = PaintingStyle.fill;
    middleFill.color = Colors.white.withOpacity(1.0);
    canvas.drawRect(Rect.fromLTRB(39 * sx, 0, 144 * sx, h), middleFill);

    Path path_4 = Path();
    path_4.moveTo(164.738 * sx, 14.8957 * sy);
    path_4.cubicTo(168.132 * sx, 20 * sy, 172.833 * sx, 30.6667 * sy, 182.833 * sx, h);
    path_4.lineTo(142.833 * sx, h);
    path_4.lineTo(142.833 * sx, 0);
    path_4.cubicTo(154.737 * sx, 0, 159.708 * sx, 7.33333 * sy, 164.738 * sx, 14.8957 * sy);
    path_4.close();

    Paint paint4Fill = Paint()..style = PaintingStyle.fill;
    paint4Fill.color = Colors.white.withOpacity(1.0);
    canvas.drawPath(path_4, paint4Fill);
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}
