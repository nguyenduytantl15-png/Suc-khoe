package vn.suckhoe.app

/** Đánh giá chỉ số – chỉ mang tính tham khảo, không thay thế chẩn đoán của bác sĩ. */
data class Status(val label: String, val color: Int, val advice: String = "")

object Health {

    fun hr(bpm: Int): Status = when {
        bpm < 50 -> Status("Thấp", C.ORANGE, "Nhịp tim lúc nghỉ dưới 50. Nếu bạn không phải người tập luyện thể thao thường xuyên, hoặc có chóng mặt, mệt, ngất – nên đi khám.")
        bpm < 60 -> Status("Hơi thấp", C.BLUE, "Thường bình thường ở người hay vận động. Đo lại khi nghỉ ngơi để chắc chắn.")
        bpm <= 100 -> Status("Bình thường", C.GREEN, "Nhịp tim lúc nghỉ của người lớn thường trong khoảng 60–100 lần/phút.")
        bpm <= 120 -> Status("Hơi cao", C.ORANGE, "Nếu bạn vừa vận động, uống cà phê hay căng thẳng thì có thể cao tạm thời. Nghỉ 5 phút rồi đo lại.")
        else -> Status("Cao", C.RED, "Nhịp tim lúc nghỉ trên 120. Nếu kéo dài hoặc kèm hồi hộp, khó thở, đau ngực – nên đi khám.")
    }

    /** Phân độ theo khuyến cáo Hội Tim mạch Việt Nam (VNHA) / ESC. */
    fun bp(sys: Int, dia: Int): Status {
        if (sys < 90 || dia < 60) {
            return Status("Huyết áp thấp", C.BLUE, "Nếu có chóng mặt, mệt, choáng khi đứng dậy – nên hỏi ý kiến bác sĩ.")
        }
        val level = maxOf(
            when { sys >= 180 -> 5; sys >= 160 -> 4; sys >= 140 -> 3; sys >= 130 -> 2; sys >= 120 -> 1; else -> 0 },
            when { dia >= 110 -> 5; dia >= 100 -> 4; dia >= 90 -> 3; dia >= 85 -> 2; dia >= 80 -> 1; else -> 0 }
        )
        return when (level) {
            0 -> Status("Tối ưu", C.GREEN, "Huyết áp rất tốt. Duy trì lối sống lành mạnh.")
            1 -> Status("Bình thường", C.GREEN, "Huyết áp bình thường.")
            2 -> Status("Bình thường cao", C.ORANGE, "Nên giảm muối, vận động đều, theo dõi huyết áp thường xuyên hơn.")
            3 -> Status("Tăng huyết áp độ 1", C.ORANGE, "Nếu nhiều lần đo đều ở mức này, nên đi khám để được tư vấn.")
            4 -> Status("Tăng huyết áp độ 2", C.RED, "Nên đi khám sớm để được bác sĩ đánh giá và điều trị.")
            else -> Status("Tăng huyết áp độ 3", C.RED, "Huyết áp rất cao. Nghỉ ngơi và đo lại; nếu vẫn cao hoặc có đau đầu dữ dội, đau ngực, khó thở, yếu liệt, nhìn mờ – đi cấp cứu ngay.")
        }
    }

    fun spo2(v: Int): Status = when {
        v >= 95 -> Status("Bình thường", C.GREEN, "SpO2 bình thường thường từ 95% trở lên.")
        v >= 93 -> Status("Hơi thấp", C.ORANGE, "Đo lại khi tay ấm, ngồi yên. Nếu vẫn thấp hoặc có khó thở – hỏi ý kiến bác sĩ.")
        v >= 90 -> Status("Thấp", C.RED, "Nên liên hệ bác sĩ / cơ sở y tế, nhất là khi kèm khó thở.")
        else -> Status("Rất thấp", C.RED, "SpO2 dưới 90% là nguy hiểm. Cần đến cơ sở y tế ngay, đặc biệt khi có khó thở, tím môi.")
    }

    fun bmi(weightKg: Double, heightCm: Double): Double? {
        if (heightCm <= 0) return null
        val m = heightCm / 100.0
        return weightKg / (m * m)
    }

    /** Ngưỡng BMI cho người châu Á (WHO khu vực Tây Thái Bình Dương). */
    fun bmiStatus(b: Double): Status = when {
        b < 18.5 -> Status("Thiếu cân", C.BLUE, "Nên bổ sung dinh dưỡng hợp lý.")
        b < 23 -> Status("Bình thường", C.GREEN, "Cân nặng hợp lý.")
        b < 25 -> Status("Thừa cân", C.ORANGE, "Nên điều chỉnh ăn uống và tăng vận động.")
        b < 30 -> Status("Béo phì độ I", C.RED, "Nên giảm cân, có thể tham khảo ý kiến bác sĩ dinh dưỡng.")
        else -> Status("Béo phì độ II", C.RED, "Nên gặp bác sĩ để có kế hoạch giảm cân an toàn.")
    }

    /** Quãng đường (km) từ số bước; sải chân ≈ 0.415 × chiều cao. */
    fun distanceKm(steps: Int, heightCm: Double): Double {
        val stride = if (heightCm > 0) heightCm * 0.415 / 100.0 else 0.7
        return steps * stride / 1000.0
    }

    /** Calo ước tính khi đi bộ (~0.04 kcal/bước cho người 70kg). */
    fun kcal(steps: Int, weightKg: Double): Double {
        val w = if (weightKg > 0) weightKg else 60.0
        return steps * 0.04 * (w / 70.0)
    }
}
