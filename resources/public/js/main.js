function $(selector) {
  return document.querySelector(selector);
}

function debounce(func, wait, immediate) {
  var timeout;

  return function executedFunction() {
    let context = this;
    let args = arguments;

    let later = function() {
      timeout = null;
      if (!immediate) func.apply(context, args);
    };

    let callNow = immediate && !timeout;
    clearTimeout(timeout);
    timeout = setTimeout(later, wait);
    if (callNow) func.apply(context, args);
  };
};

function renderPost() {
  let element = $("#post-content");
  let html = element.getAttribute('data-contents');
  let options = {
    USE_PROFILES: {
      html: true,
      svg: true,
    },
    ADD_ATTR: ["target"],
    FORCE_BODY: true,
  };
  element.innerHTML = DOMPurify.sanitize(html, options);
  updateMediaQueries();
  scopePostCSS();
}

function updateMediaQueries() {
  let container = $("#post-content");
  let offset = window.innerWidth - container.offsetWidth;
  Array.from(document.styleSheets)
    .filter(sheet => container.contains(sheet.ownerNode))
    .flatMap(sheet => Array.from(sheet.cssRules))
    .filter(rule => !!rule.media)
    .forEach(rule => {
      rule.media.mediaText = rule.media.mediaText.replace(
        /-width: (\d+)px/, (_, width) => `-width: ${parseInt(width) + offset}px`
      );
    });
}

function scopeRule(rule) {
  if (!!rule.selectorText) {
    rule.selectorText = "#post-content " + rule.selectorText;
  }
  if (!!rule.cssRules) {
    Array.from(rule.cssRules).forEach(scopeRule);
  }
}

function scopePostCSS() {
  let container = $("#post-content");
  Array.from(document.styleSheets)
    .filter(sheet => container.contains(sheet.ownerNode))
    .flatMap(sheet => Array.from(sheet.cssRules))
    .forEach(scopeRule);
}

// TODO maybe store positions on the backend

function resumePosition(elt) {
  const itemId = elt.getAttribute('data-item-id');
  const positions = JSON.parse(localStorage.getItem('positions'));
  const value = positions && positions[itemId] && positions[itemId].value;
  if (value) {
    window.scrollTo(0, value);
  }
  // TODO clean up `positions` based on `time` if it's getting big
}

const savePosition = debounce((elt) => {
  if (document.body.contains(elt)) {
    const itemId = elt.getAttribute('data-item-id');
    let positions = JSON.parse(localStorage.getItem('positions')) || {};
    positions[itemId] = {value: window.pageYOffset, time: new Date().getTime()};
    localStorage.positions = JSON.stringify(positions);
  }
}, 200, false);

window.onload = () => {
  document.body.addEventListener("yak/open-new-tab", function(evt){
      window.open(evt.detail.value, "_blank");
  })
};

function set_timezone(url, csrf_token) {
  fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded'
    },
    body: new URLSearchParams({
      '__anti-forgery-token': csrf_token,
      ":user/timezone*": Intl.DateTimeFormat().resolvedOptions().timeZone
    }).toString()
  })
}
