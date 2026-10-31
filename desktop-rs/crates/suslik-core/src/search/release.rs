//! Что известно о раздаче по её названию и размеру. Повторяет `ReleaseModels.kt`.

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Resolution {
    Uhd4k,
    Fhd1080p,
    Hd720p,
    Sd,
    Unknown,
}

impl Resolution {
    pub fn label(self) -> &'static str {
        match self {
            Self::Uhd4k => "4K",
            Self::Fhd1080p => "1080p",
            Self::Hd720p => "720p",
            Self::Sd => "SD",
            Self::Unknown => "",
        }
    }
    pub fn order(self) -> i32 {
        match self {
            Self::Uhd4k => 4,
            Self::Fhd1080p => 3,
            Self::Hd720p => 2,
            Self::Sd => 1,
            Self::Unknown => 0,
        }
    }
    pub fn is_hd_or_better(self) -> bool {
        matches!(self, Self::Uhd4k | Self::Fhd1080p | Self::Hd720p)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum VideoCodec {
    Hevc,
    Avc,
    Av1,
    Other,
    Unknown,
}

impl VideoCodec {
    pub fn label(self) -> &'static str {
        match self {
            Self::Hevc => "HEVC",
            Self::Avc => "AVC",
            Self::Av1 => "AV1",
            Self::Other => "Other",
            Self::Unknown => "",
        }
    }
    /// HEVC/AV1 сжимают примерно вдвое лучше AVC — то, что нужно мобильной сети.
    pub fn is_efficient(self) -> bool {
        matches!(self, Self::Hevc | Self::Av1)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum RipType {
    Remux,
    Bluray,
    Bdrip,
    Webdl,
    Webrip,
    Hdtv,
    Dvdrip,
    Cam,
    Unknown,
}

impl RipType {
    pub fn label(self) -> &'static str {
        match self {
            Self::Remux => "BDRemux",
            Self::Bluray => "Blu-ray",
            Self::Bdrip => "BDRip",
            Self::Webdl => "WEB-DL",
            Self::Webrip => "WEBRip",
            Self::Hdtv => "HDTV",
            Self::Dvdrip => "DVDRip",
            Self::Cam => "CAM/Экранка",
            Self::Unknown => "",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum HdrType {
    Dv,
    Hdr10Plus,
    Hdr10,
    None,
}

impl HdrType {
    pub fn label(self) -> &'static str {
        match self {
            Self::Dv => "Dolby Vision",
            Self::Hdr10Plus => "HDR10+",
            Self::Hdr10 => "HDR",
            Self::None => "",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum VoiceType {
    Dub,
    Mvo,
    Dvo,
    Avo,
    Original,
    Unknown,
}

impl VoiceType {
    pub fn label(self) -> &'static str {
        match self {
            Self::Dub => "Дубляж",
            Self::Mvo => "Многоголосый",
            Self::Dvo => "Двухголосый",
            Self::Avo => "Авторский",
            Self::Original => "Оригинал/Sub",
            Self::Unknown => "",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum BitrateTier {
    Light,
    Medium,
    Heavy,
}

impl BitrateTier {
    pub fn label(self) -> &'static str {
        match self {
            Self::Light => "Легкий",
            Self::Medium => "Оптимальный",
            Self::Heavy => "Тяжёлый",
        }
    }
    pub fn hint(self) -> &'static str {
        match self {
            Self::Light => "Потянет моб. сеть",
            Self::Medium => "Хороший 4G / Wi-Fi",
            Self::Heavy => "Высокий битрейт / Домашний Wi-Fi",
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct ReleaseInfo {
    pub clean_title: String,
    pub year: Option<i32>,
    pub resolution: Resolution,
    pub codec: VideoCodec,
    pub rip_type: RipType,
    pub hdr: HdrType,
    pub voice: VoiceType,
    pub is_cam: bool,
    pub is_collection: bool,
    pub collection_count: Option<i32>,
    pub episodes_count: Option<i32>,
    pub estimated_bitrate_mbps: f32,
    pub bitrate_tier: BitrateTier,
}

impl ReleaseInfo {
    /// Годится для мобильной сети: без экранки, 4K и Remux, битрейт небольшой.
    pub fn is_mobile_friendly(&self) -> bool {
        let b = self.estimated_bitrate_mbps;
        !self.is_cam
            && self.resolution != Resolution::Uhd4k
            && self.rip_type != RipType::Remux
            && self.rip_type != RipType::Bluray
            && ((0.05..=4.5).contains(&b) || (b == 0.0 && self.resolution == Resolution::Hd720p))
    }

    /// Метки для карточки раздачи: ["1080p", "HEVC", "WEB-DL", "Дубляж", "~2.8 Мбит/с"].
    pub fn badges(&self) -> Vec<String> {
        let mut out = Vec::new();
        if self.resolution != Resolution::Unknown {
            out.push(self.resolution.label().to_string());
        }
        if !matches!(self.codec, VideoCodec::Unknown | VideoCodec::Other) {
            out.push(self.codec.label().to_string());
        }
        if self.hdr != HdrType::None {
            out.push(self.hdr.label().to_string());
        }
        if self.rip_type != RipType::Unknown {
            out.push(self.rip_type.label().to_string());
        }
        if self.is_collection {
            out.push(match self.collection_count {
                Some(n) => format!("Коллекция ({n})"),
                None => "Коллекция".to_string(),
            });
        }
        if self.voice != VoiceType::Unknown {
            out.push(self.voice.label().to_string());
        }
        if self.estimated_bitrate_mbps > 0.0 {
            let suffix = if self.is_collection { " / фильм" } else { "" };
            let rate = if self.estimated_bitrate_mbps < 1.0 {
                "< 1 Мбит/с".to_string()
            } else {
                format!("~{:.1} Мбит/с", self.estimated_bitrate_mbps)
            };
            out.push(rate + suffix);
        }
        out
    }
}
